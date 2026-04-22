package instr;

import common.IdentityMapper;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.io.InputStream;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.commons.LocalVariablesSorter;

public class SyncTransformer implements ClassFileTransformer {

    private final String extraExclude; // optional extra package prefix to skip; may be null

    public SyncTransformer()                    { this.extraExclude = null; }
    public SyncTransformer(String extraExclude) { this.extraExclude = extraExclude; }

    // ---- Site ID registry (derived from stable site strings) ----
    // The same site string must map to the same numeric id across capture and
    // replay, regardless of class transform order.
    private static final ConcurrentHashMap<String, Long> siteRegistry = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, String> siteIdOwners = new ConcurrentHashMap<>();

    private static long deterministicSiteId(String siteString) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(siteString.getBytes(StandardCharsets.UTF_8));
            long id = ((long) (digest[0] & 0x7F) << 56)
                    | ((long) (digest[1] & 0xFF) << 48)
                    | ((long) (digest[2] & 0xFF) << 40)
                    | ((long) (digest[3] & 0xFF) << 32)
                    | ((long) (digest[4] & 0xFF) << 24)
                    | ((long) (digest[5] & 0xFF) << 16)
                    | ((long) (digest[6] & 0xFF) << 8)
                    | (long) (digest[7] & 0xFF);
            return id == 0L ? 1L : id;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive deterministic site id for " + siteString, e);
        }
    }

    public static long registerSiteId(String siteString) {
        return siteRegistry.computeIfAbsent(siteString, key -> {
            long siteId = deterministicSiteId(key);
            String existing = siteIdOwners.putIfAbsent(siteId, key);
            if (existing != null && !existing.equals(key)) {
                throw new IllegalStateException(
                        "Deterministic site id collision for id=" + siteId
                                + " between `" + existing + "` and `" + key + "`");
            }
            if (isReplayIrrelevantGroovySite(key)) {
                IdentityMapper.registerIgnoredSite(siteId);
            }
            return siteId;
        });
    }

    private static boolean isGroovyBootstrapClass(String className) {
        return className.startsWith("org/codehaus/groovy/")
                || className.startsWith("groovy/lang/")
                || className.startsWith("groovy/util/")
                || className.startsWith("groovyjarjarasm/");
    }

    private static boolean isLog4jBenchmarkClass(String className) {
        return className.equals("org/apache/log4j/helpers/Test54325")
                || className.equals("org/apache/log4j/Test38137")
                || className.equals("org/apache/log4j/Test50463");
    }

    private static boolean isLog4jLibraryClass(String className) {
        return className.startsWith("org/apache/log4j/")
                && !isLog4jBenchmarkClass(className);
    }

    private static boolean isDependencyClass(String className) {
        return className.startsWith("java/")
                || className.startsWith("javax/")
                || className.startsWith("jdk/")
                || className.startsWith("sun/")
                || className.startsWith("com/sun/")
                || isGroovyBootstrapClass(className)
                || isLog4jLibraryClass(className)
                || className.startsWith("org/apache/tools/ant/")
                || className.startsWith("org/apache/lucene/analysis/")
                || className.startsWith("org/apache/lucene/index/IndexFileDeleter")
                || className.startsWith("org/apache/lucene/index/ConcurrentMergeScheduler")
                || className.startsWith("org/apache/lucene/util/_TestUtil293")
                || className.startsWith("org/objectweb/asm/")
                || className.startsWith("junit/")
                || className.startsWith("capture/")
                || className.startsWith("replay/")
                || className.startsWith("common/")
                || className.startsWith("instr/")
                || className.startsWith("coring/")
                || className.startsWith("edu/illinois/jacontebe/")
                || className.startsWith("edu/illinois/jacontebe/monitors/");
    }

    private static boolean isReplayIrrelevantGroovySite(String siteString) {
        int memberSep = siteString.indexOf('.');
        String owner = memberSep >= 0 ? siteString.substring(0, memberSep) : siteString;
        return owner.startsWith("org/codehaus/groovy/reflection/")
                || owner.startsWith("org/codehaus/groovy/runtime/metaclass/")
                || owner.startsWith("groovy/lang/Meta")
                || (isGeneratedGroovyScriptClass(owner)
                    && (siteString.contains(".<clinit>#")
                        || siteString.contains("#throw_")));
    }

    private static boolean isGeneratedGroovyScriptClass(String className) {
        int slash = className.lastIndexOf('/');
        String simpleName = slash >= 0 ? className.substring(slash + 1) : className;
        return simpleName.matches("Script\\d+(\\$.*)?");
    }

    private static boolean isGeneratedGroovyScriptSource(String sourceFile) {
        return sourceFile != null && sourceFile.matches("Script\\d+\\.groovy");
    }

    private static boolean isGeneratedGroovyScriptOwned(String className, String sourceFile) {
        return isGeneratedGroovyScriptClass(className)
                || isGeneratedGroovyScriptSource(sourceFile);
    }

    private static boolean shouldInstrumentClinit(String className, String sourceFile) {
        return !isDependencyClass(className)
                && !isGeneratedGroovyScriptOwned(className, sourceFile);
    }

    private static boolean shouldSkipBootstrapFieldTraffic(String currentClass, String fieldOwner) {
        return isGroovyBootstrapClass(currentClass) && isGroovyBootstrapClass(fieldOwner);
    }

    private static boolean shouldSkipGeneratedScriptFieldTraffic(String currentClass, String currentSourceFile,
                                                                 String fieldOwner) {
        return isGeneratedGroovyScriptOwned(currentClass, currentSourceFile)
                && (isGeneratedGroovyScriptClass(fieldOwner) || isGroovyBootstrapClass(fieldOwner));
    }

    private static boolean shouldSkipDependencyFieldTraffic(String currentClass, String fieldOwner) {
        return isDependencyClass(currentClass) || isDependencyClass(fieldOwner);
    }

    private static boolean shouldSkipGroovyServletInitFieldTraffic(String currentClass, String methodName) {
        return currentClass.startsWith("groovy/servlet/")
                && ("<init>".equals(methodName) || "<clinit>".equals(methodName));
    }

    private static boolean shouldSkipGroovyServletTraffic(String currentClass) {
        return currentClass.startsWith("groovy/servlet/");
    }

    private static boolean shouldSkipLucene1544SetupTraffic(String currentClass, String methodName) {
        return currentClass.equals("org/apache/lucene/Test1544")
                && ("setUp".equals(methodName)
                    || "<init>".equals(methodName)
                    || "tearDown".equals(methodName)
                    || "createIndex".equals(methodName)
                    || "getWriter".equals(methodName));
    }

    private static boolean shouldSkipLuceneBootstrapTraffic(String currentClass, String methodName) {
        return (currentClass.equals("org/apache/lucene/index/IndexWriter")
                    && ("<init>".equals(methodName) || "init".equals(methodName)))
                || (currentClass.equals("org/apache/lucene/index/IndexFileDeleter")
                    && "<init>".equals(methodName));
    }

    private static boolean shouldSkipExceptionTraffic(String currentClass, String sourceFile) {
        return isGeneratedGroovyScriptOwned(currentClass, sourceFile)
                || shouldSkipGroovyServletTraffic(currentClass)
                || isDependencyClass(currentClass);
    }

    public static void resetSiteRegistry() {
        siteRegistry.clear();
        siteIdOwners.clear();
    }

    // ---- Global volatile field resolution ----
    // Maps "owner/className" → set of volatile field names.
    // Populated as classes are transformed; on cache miss for cross-class
    // field accesses, reads the owner class's bytecode via the classloader.
    private static final ConcurrentHashMap<String, Set<String>> volatileFieldCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Set<String>> finalFieldCache = new ConcurrentHashMap<>();

    /**
     * Resolves whether a field is volatile by checking the declaring (owner) class.
     * Checks the global cache first; on miss, reads the owner's class bytes via
     * ASM.
     */
    static boolean isFieldVolatile(ClassLoader loader, String owner, String fieldName) {
        Set<String> fields = volatileFieldCache.get(owner);
        if (fields != null) {
            return fields.contains(fieldName);
        }
        // Cache miss — read the owner class's bytecode to discover its volatile fields
        return resolveAndCache(loader, owner, fieldName);
    }

    static boolean isFieldFinal(ClassLoader loader, String owner, String fieldName) {
        if (!finalFieldCache.containsKey(owner)) {
            resolveAndCache(loader, owner, fieldName);
        }
        Set<String> fields = finalFieldCache.get(owner);
        return fields != null && fields.contains(fieldName);
    }

    // Pre-populate caches from the raw class bytes available during transform().
    // This handles dynamically-generated classes (e.g. Groovy enums) that have no
    // .class file on the classpath and would otherwise be missed by resolveAndCache.
    static void cacheFromBytes(String className, byte[] classfileBuffer) {
        if (finalFieldCache.containsKey(className)) return;
        try {
            Set<String> volFields = ConcurrentHashMap.newKeySet();
            Set<String> finFields = ConcurrentHashMap.newKeySet();
            new ClassReader(classfileBuffer).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    if ((access & Opcodes.ACC_VOLATILE) != 0) volFields.add(name);
                    if ((access & Opcodes.ACC_FINAL)    != 0) finFields.add(name);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
            volatileFieldCache.putIfAbsent(className, volFields);
            finalFieldCache.putIfAbsent(className, finFields);
        } catch (Exception ignored) {}
    }

    private static boolean resolveAndCache(ClassLoader loader, String owner, String fieldName) {
        try {
            InputStream is = null;
            if (loader != null) {
                is = loader.getResourceAsStream(owner + ".class");
            }
            if (is == null) {
                is = ClassLoader.getSystemResourceAsStream(owner + ".class");
            }
            if (is == null)
                return false;

            ClassReader cr = new ClassReader(is);
            Set<String> volFields = ConcurrentHashMap.newKeySet();
            Set<String> finFields = ConcurrentHashMap.newKeySet();
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature,
                        Object value) {
                    if ((access & Opcodes.ACC_VOLATILE) != 0) {
                        volFields.add(name);
                    }
                    if ((access & Opcodes.ACC_FINAL) != 0) {
                        finFields.add(name);
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);

            volatileFieldCache.put(owner, volFields);
            finalFieldCache.put(owner, finFields);
            return volFields.contains(fieldName);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        // prevent recursion
        if (className == null || className.startsWith("instr/") ||
                className.startsWith("common/") || className.startsWith("capture/") ||
                className.startsWith("replay/") || className.startsWith("java/") || className.startsWith("jdk/")
                || className.startsWith("sun/") || className.startsWith("com/sun/")
                || className.startsWith("agent/shaded/")
                || className.startsWith("edu/illinois/jacontebe/monitors/")
                || className.contains("$$")          // CGLib/ASM runtime-generated proxy classes
                || className.startsWith("org/mockito/")
                || className.startsWith("net/sf/cglib/")
                || className.startsWith("org/objenesis/")
                || className.equals("org/codehaus/groovy/runtime/DefaultGroovyMethods")
                // Groovy's internal sync primitives (LockableObject, LazyReference, etc.)
                // extend AQS and are instantiated concurrently, producing nondeterministic
                // birth counts across runs.  They never contain application-level races.
                || className.startsWith("org/codehaus/groovy/util/")) {
            return null;
        }
        if (extraExclude != null && className.startsWith(extraExclude)) {
            return null;
        }

        // Pre-populate field-modifier caches from the class bytes we already have.
        // resolveAndCache() falls back to getResourceAsStream, which returns null for
        // dynamically-generated classes (e.g. Groovy enums compiled at runtime).
        cacheFromBytes(className, classfileBuffer);

        // set up ASM to read and write the class
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            // Class file version is at bytes 6-7 (major version).
            // Version < 50 (pre-Java 6): no StackMapTable required; old JVM verifier
            // handles any bytecode including JSR/RET subroutines. COMPUTE_MAXS is safe
            // because there are no existing frame entries to conflict with our
            // LocalVariablesSorter remapping.
            // Version >= 50 (Java 6+): StackMapTable is required and must be regenerated
            // after LocalVariablesSorter remaps local variables. COMPUTE_FRAMES does this,
            // using SafeClassWriter to avoid triggering defineClass for app classes.
            int classVersion = ((classfileBuffer[6] & 0xFF) << 8) | (classfileBuffer[7] & 0xFF);
            ClassWriter writer = classVersion < 50
                    ? new ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
                    : new SafeClassWriter(reader, loader);

            SyncClassVisitor visitor = new SyncClassVisitor(writer, className, loader);
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            return writer.toByteArray();
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    // ClassWriter for Java 6+ classes (version >= 50). Uses resource-stream lookup
    // for app classes to avoid triggering defineClass (which conflicts with Mockito
    // cglib's reflected defineClass call), and Class.forName for JDK bootstrap classes
    // (which live in JIMAGE modules, not accessible via getResourceAsStream).
    private static class SafeClassWriter extends ClassWriter {
        private final ClassLoader appLoader;

        SafeClassWriter(ClassReader reader, ClassLoader appLoader) {
            super(reader, COMPUTE_FRAMES);
            this.appLoader = appLoader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                java.util.List<String> chain = new java.util.ArrayList<>();
                for (String t = type1; t != null; t = superOf(t)) chain.add(t);
                chain.add("java/lang/Object");
                for (String ancestor : chain) {
                    if (isAncestorOrSelf(type2, ancestor)) return ancestor;
                }
            } catch (Throwable ignored) {}
            return "java/lang/Object";
        }

        private String superOf(String internalName) {
            try {
                if (isBootstrap(internalName)) {
                    ClassLoader cl = appLoader != null ? appLoader : ClassLoader.getSystemClassLoader();
                    Class<?> c = Class.forName(internalName.replace('/', '.'), false, cl);
                    Class<?> s = c.getSuperclass();
                    if (s == null || s == Object.class) return null;
                    return s.getName().replace('.', '/');
                }
                try (java.io.InputStream is = openClassBytes(internalName)) {
                    if (is == null) return null;
                    String sup = new ClassReader(is).getSuperName();
                    return (sup == null || "java/lang/Object".equals(sup)) ? null : sup;
                }
            } catch (Throwable t) { return null; }
        }

        private boolean isAncestorOrSelf(String type, String ancestor) {
            if ("java/lang/Object".equals(ancestor)) return true;
            for (String t = type; t != null; t = superOf(t)) {
                if (t.equals(ancestor)) return true;
            }
            return false;
        }

        private static boolean isBootstrap(String n) {
            return n.startsWith("java/") || n.startsWith("javax/")
                || n.startsWith("sun/") || n.startsWith("jdk/")
                || n.startsWith("com/sun/");
        }

        private java.io.InputStream openClassBytes(String internalName) {
            String resource = internalName + ".class";
            java.io.InputStream is = appLoader != null
                    ? appLoader.getResourceAsStream(resource) : null;
            return is != null ? is : ClassLoader.getSystemResourceAsStream(resource);
        }
    }

    // begin by wrapping the class by wrapping methods within the class
    static class SyncClassVisitor extends ClassVisitor {
        private final String className;
        private final ClassLoader loader;
        private final String monitorClass;
        private final String monitorMethod;
        private final boolean isReplay = "REPLAY".equals(System.getProperty("tool.mode"));
        private String sourceFile;

        public SyncClassVisitor(ClassVisitor cv, String className, ClassLoader loader) {
            super(Opcodes.ASM9, cv);
            this.className = className;
            this.loader = loader;
            boolean isReplay = "REPLAY".equals(System.getProperty("tool.mode"));
            this.monitorClass = isReplay ? "replay/ReplayMonitor" : "capture/CaptureMonitor";
            this.monitorMethod = isReplay ? "checkSync" : "logSync";
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            // Register volatile fields in the global cache as we visit them
            if ((access & Opcodes.ACC_VOLATILE) != 0) {
                volatileFieldCache
                        .computeIfAbsent(className, k -> ConcurrentHashMap.newKeySet())
                        .add(name);
            }
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public void visitSource(String source, String debug) {
            this.sourceFile = source;
            super.visitSource(source, debug);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            boolean isStaticMethod = (access & Opcodes.ACC_STATIC) != 0;
            boolean isSynchronizedMethod = (access & Opcodes.ACC_SYNCHRONIZED) != 0
                    && !isStaticMethod && !name.equals("<clinit>");
            int emittedAccess = (isReplay && isSynchronizedMethod) ? (access & ~Opcodes.ACC_SYNCHRONIZED) : access;
            MethodVisitor mv = super.visitMethod(emittedAccess, name, descriptor, signature, exceptions);

            if (name.equals("<clinit>")) {
                if (!SyncTransformer.shouldInstrumentClinit(className, sourceFile)) {
                    return new JSRInlinerAdapter(mv, emittedAccess, name, descriptor, signature, exceptions);
                }
                MethodVisitor synced = new SyncMethodVisitor(emittedAccess, descriptor, mv, className, name, sourceFile, loader,
                        isSynchronizedMethod, isStaticMethod);
                MethodVisitor clinit = new ClinitMethodVisitor(synced, className, monitorClass, monitorMethod);
                return new JSRInlinerAdapter(clinit, emittedAccess, name, descriptor, signature, exceptions);
            }

            MethodVisitor synced = new SyncMethodVisitor(emittedAccess, descriptor, mv, className, name, sourceFile, loader,
                    isSynchronizedMethod, isStaticMethod);
            return new JSRInlinerAdapter(synced, emittedAccess, name, descriptor, signature, exceptions);
        }
    }

    static class SyncMethodVisitor extends LocalVariablesSorter {
        private final String className;
        private final String methodName;
        private final String methodDescriptor;
        private final String sourceFile;
        private final ClassLoader loader;
        private int instructionId = 0;

        // Tracks pending NEW instructions (type name → allocation site string) so we
        // can emit registerAllocation after the matching INVOKESPECIAL <init> call.
        // Outer = most recent NEW (stack order: top = innermost allocation).
        private final Deque<String> pendingNewTypes = new ArrayDeque<>();
        private final Deque<String> pendingNewSites = new ArrayDeque<>();

        private final boolean isReplay = "REPLAY".equals(System.getProperty("tool.mode"));
        private final String monitorClass = isReplay ? "replay/ReplayMonitor" : "capture/CaptureMonitor";
        private final String monitorDescriptor = "(ILjava/lang/Object;J)V";

        // Guard: in <init> methods, 'this' is uninitializedThis until the first
        // INVOKESPECIAL <init> (super/this constructor call).  Instrumenting it
        // before that point causes VerifyError: Bad type on operand stack.
        // superInitCalled starts false only for constructors; all other methods
        // treat it as already true so instrumentation proceeds normally.
        private final boolean isConstructorMethod;
        private boolean superInitCalled;
        private final boolean isSynchronizedMethod;
        private final boolean isStaticMethod;

        private boolean isArrayLoad(int opcode) {
            return opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD;
        }

        private boolean isArrayStore(int opcode) {
            return opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE;
        }

        private boolean shouldSkipNonBenchmarkArrayTraffic(int opcode) {
            if (!SyncTransformer.isGeneratedGroovyScriptOwned(className, sourceFile)
                    && !SyncTransformer.isDependencyClass(className)
                    && !SyncTransformer.isGroovyBootstrapClass(className)
                    && !SyncTransformer.shouldSkipGroovyServletTraffic(className)
                    && !SyncTransformer.shouldSkipLucene1544SetupTraffic(className, methodName)
                    && !SyncTransformer.shouldSkipLuceneBootstrapTraffic(className, methodName)) {
                return false;
            }
            return isArrayLoad(opcode)
                    || isArrayStore(opcode)
                    || opcode == Opcodes.LALOAD
                    || opcode == Opcodes.DALOAD
                    || opcode == Opcodes.LASTORE
                    || opcode == Opcodes.DASTORE;
        }

        private boolean shouldSkipCollectionTraffic() {
            return SyncTransformer.isGeneratedGroovyScriptOwned(className, sourceFile)
                    || SyncTransformer.isDependencyClass(className)
                    || SyncTransformer.shouldSkipGroovyServletTraffic(className)
                    || SyncTransformer.shouldSkipGroovyServletInitFieldTraffic(className, methodName)
                    || SyncTransformer.shouldSkipLucene1544SetupTraffic(className, methodName)
                    || SyncTransformer.shouldSkipLuceneBootstrapTraffic(className, methodName);
        }

        private boolean shouldSkipAtomicTraffic() {
            return SyncTransformer.isGeneratedGroovyScriptOwned(className, sourceFile)
                    || SyncTransformer.isDependencyClass(className);
        }

        // ---- Allocation tracking ----
        // NEW: push the type and allocation site; matched by visitMethodInsn.
        // NEWARRAY/ANEWARRAY/MULTIANEWARRAY: no <init>, so register inline.

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (!superInitCalled) {
                // Track NEW even before super/this() so visitMethodInsn can tell whether
                // an INVOKESPECIAL <init> belongs to a nested object allocation (e.g.
                // "this(null, new Binding(), config)") or is the actual super/this call.
                // Push null as the allocation site — no instrumentation is emitted yet.
                if (opcode == Opcodes.NEW) {
                    pendingNewTypes.push(type);
                    pendingNewSites.push("");
                }
                super.visitTypeInsn(opcode, type);
                return;
            }
            if (opcode == Opcodes.NEW) {
                String allocSite = className + "." + methodName + "#alloc_" + instructionId++;
                pendingNewTypes.push(type);
                pendingNewSites.push(allocSite);
                super.visitTypeInsn(opcode, type);
            } else if (opcode == Opcodes.ANEWARRAY) {
                String allocSite = className + "." + methodName + "#alloc_" + instructionId++;
                long allocSiteId = SyncTransformer.registerSiteId(allocSite);
                super.visitTypeInsn(opcode, type);
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(allocSiteId);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "common/IdentityMapper", "registerAllocation",
                        "(Ljava/lang/Object;J)V", false);
            } else {
                super.visitTypeInsn(opcode, type);
            }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            if (!superInitCalled) {
                super.visitIntInsn(opcode, operand);
                return;
            }
            if (opcode == Opcodes.NEWARRAY) {
                String allocSite = className + "." + methodName + "#alloc_" + instructionId++;
                long allocSiteId = SyncTransformer.registerSiteId(allocSite);
                super.visitIntInsn(opcode, operand);
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(allocSiteId);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "common/IdentityMapper", "registerAllocation",
                        "(Ljava/lang/Object;J)V", false);
            } else {
                super.visitIntInsn(opcode, operand);
            }
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            if (!superInitCalled) {
                super.visitMultiANewArrayInsn(descriptor, numDimensions);
                return;
            }
            String allocSite = className + "." + methodName + "#alloc_" + instructionId++;
            long allocSiteId = SyncTransformer.registerSiteId(allocSite);
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(allocSiteId);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "common/IdentityMapper", "registerAllocation",
                    "(Ljava/lang/Object;J)V", false);
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (!superInitCalled) {
                super.visitLdcInsn(value);
                return;
            }
            super.visitLdcInsn(value);
            if (value instanceof String) {
                // String literals are JVM-interned: one canonical object per unique value.
                // Use the string content as the site key so the same literal gets the
                // same BirthId.PoolString across capture and replay regardless of class-load order.
                // Heap strings constructed via `new String(...)` are a distinct bytecode
                // path (NEW + INVOKESPECIAL) and are handled by visitTypeInsn /
                // visitMethodInsn with a position-based BirthId.Heap, not here.
                String siteKey = "ldc:string:" + value;
                long siteId = SyncTransformer.registerSiteId(siteKey);
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(siteId);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "common/IdentityMapper", "registerPoolString",
                        "(Ljava/lang/String;J)V", false);
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... bsmArgs) {
            if (!superInitCalled) {
                super.visitInvokeDynamicInsn(name, descriptor, bsm, bsmArgs);
                return;
            }
            super.visitInvokeDynamicInsn(name, descriptor, bsm, bsmArgs);
            // Register lambda/method-reference instances created by LambdaMetafactory.
            // Stateless lambdas are JVM-cached singletons; registerAllocation is idempotent
            // so repeated calls for the same object are safe.
            // Capturing lambdas create a new instance per call; the site counter tracks
            // each instance just like regular NEW allocations.
            if (bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
                String allocSite = className + "." + methodName + "#lambda_" + instructionId++;
                long siteId = SyncTransformer.registerSiteId(allocSite);
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(siteId);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "common/IdentityMapper", "registerAllocation",
                        "(Ljava/lang/Object;J)V", false);
            }
        }

        private static boolean isAtomicArrayClass(String owner) {
            return owner.equals("java/util/concurrent/atomic/AtomicIntegerArray")
                    || owner.equals("java/util/concurrent/atomic/AtomicLongArray")
                    || owner.equals("java/util/concurrent/atomic/AtomicReferenceArray");
        }

        public SyncMethodVisitor(int access, String descriptor, MethodVisitor mv, String className, String methodName,
                String sourceFile, ClassLoader loader, boolean isSynchronizedMethod, boolean isStaticMethod) {
            super(Opcodes.ASM9, access, descriptor, mv);
            this.className = className;
            this.methodName = methodName;
            this.methodDescriptor = descriptor;
            this.sourceFile = sourceFile;
            this.loader = loader;
            this.isSynchronizedMethod = isSynchronizedMethod;
            this.isStaticMethod = isStaticMethod;
            this.isConstructorMethod = "<init>".equals(methodName);
            // For constructors, hold off instrumentation until super/this() is called.
            // For all other methods, 'this' is always initialized so act as if done.
            this.superInitCalled = !isConstructorMethod;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (isSynchronizedMethod && !SyncTransformer.isDependencyClass(className)) {
                emitSynchronizedMethodSync(1, "sync_enter");
            }
        }

        private void emitSynchronizedMethodSync(int eventType, String suffix) {
            if (isStaticMethod) {
                mv.visitLdcInsn(Type.getObjectType(className));
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
            }
            logSyncCall(eventType, SyncTransformer.registerSiteId(className + "." + methodName + "#" + suffix));
        }

        @Override
        public void visitInsn(int opcode) {
            if (!superInitCalled) {
                super.visitInsn(opcode);
                return;
            }
            if (shouldSkipNonBenchmarkArrayTraffic(opcode)) {
                super.visitInsn(opcode);
                return;
            }
            // Handle explicit/implicit throws — log before the throw so the
            // coordinator sees EXCEPTION_THROW in the right sequence position.
            if (opcode == Opcodes.ATHROW) {
                if (!SyncTransformer.shouldSkipExceptionTraffic(className, sourceFile)) {
                    String logMethod = isReplay ? "checkException" : "logException";
                    String siteString = className + "." + methodName + "#throw_" + instructionId++;
                    long siteId = SyncTransformer.registerSiteId(siteString);
                    // Stack: [..., exception]
                    mv.visitInsn(Opcodes.DUP);       // [..., exception, exception]
                    mv.visitLdcInsn(siteId);          // [..., exception, exception, siteId]
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, logMethod,
                            "(Ljava/lang/Object;J)V", false);
                    // Stack restored to [..., exception]; ATHROW executes below via super.visitInsn
                }
            }
            if (isSynchronizedMethod
                    && !SyncTransformer.isDependencyClass(className)
                    && ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW)) {
                emitSynchronizedMethodSync(2, "sync_exit");
            }
            // Handle Intrinsic Locks
            if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                if (SyncTransformer.isDependencyClass(className)) {
                    super.visitInsn(opcode);
                    return;
                }
                String monitorMethod = isReplay ? "checkSync" : "logSync";
                int eventType = (opcode == Opcodes.MONITORENTER) ? 1 : 2;
                String siteString = className + "." + methodName + "#" + instructionId++;
                long siteId = SyncTransformer.registerSiteId(siteString);

                mv.visitInsn(Opcodes.DUP); // Keep the lock object
                mv.visitLdcInsn(eventType); // [lock, lock, type]
                mv.visitInsn(Opcodes.SWAP); // [lock, type, lock]
                mv.visitLdcInsn(siteId); // [lock, type, lock, siteId]

                // Matches CaptureMonitor.logSync(int type, Object lock, int siteId)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod,
                        "(ILjava/lang/Object;J)V", false);
                // Handle long/double array operations (category-2 values take 2 stack slots)
            } else if (opcode == Opcodes.LASTORE || opcode == Opcodes.DASTORE || opcode == Opcodes.LALOAD
                    || opcode == Opcodes.DALOAD) {
                String monitorMethod = isReplay ? "checkArray" : "logArray";
                int eventType = (opcode == Opcodes.LALOAD || opcode == Opcodes.DALOAD) ? 7 : 8;
                String siteString = className + "." + methodName + "#" + instructionId++;
                long siteId = SyncTransformer.registerSiteId(siteString);

                if (opcode == Opcodes.LALOAD || opcode == Opcodes.DALOAD) {
                    if (isReplay) {
                        // Stack: [arrayRef, index] — atomically read inside the coordination lock.
                        mv.visitLdcInsn(siteId);
                        if (opcode == Opcodes.DALOAD) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkArrayReadDouble",
                                "(Ljava/lang/Object;IJ)D", false);
                        } else {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkArrayReadLong",
                                "(Ljava/lang/Object;IJ)J", false);
                        }
                        return; // read value is on stack; skip super.visitInsn
                    }
                    mv.visitInsn(Opcodes.DUP2); // [arrayRef, index, arrayRef, index]
                    mv.visitLdcInsn(eventType); // [arrayRef, index, arrayRef, index, type]
                    mv.visitInsn(Opcodes.DUP_X2); // [arrayRef, index, type, arrayRef, index, type]
                    mv.visitInsn(Opcodes.POP); // [arrayRef, index, type, arrayRef, index]
                    mv.visitLdcInsn(siteId); // [arrayRef, index, type, arrayRef, index, siteId]

                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod,
                            "(ILjava/lang/Object;IJ)V", false);
                } else {
                    // LASTORE / DASTORE
                    if (isReplay) {
                        // Stack: [arrayRef, index, wideValue(cat2)]
                        // Atomically write inside the coordination lock.
                        Type valType = (opcode == Opcodes.DASTORE) ? Type.DOUBLE_TYPE : Type.LONG_TYPE;
                        int valueSlot = newLocal(valType);
                        mv.visitVarInsn(valType.getOpcode(Opcodes.ISTORE), valueSlot);
                        int indexSlot = newLocal(Type.INT_TYPE);
                        mv.visitVarInsn(Opcodes.ISTORE, indexSlot);
                        int arraySlot = newLocal(Type.getObjectType("java/lang/Object"));
                        mv.visitVarInsn(Opcodes.ASTORE, arraySlot);
                        mv.visitVarInsn(valType.getOpcode(Opcodes.ILOAD), valueSlot);
                        mv.visitVarInsn(Opcodes.ALOAD, arraySlot);
                        mv.visitVarInsn(Opcodes.ILOAD, indexSlot);
                        mv.visitLdcInsn(siteId);
                        if (opcode == Opcodes.DASTORE) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkArrayWriteDouble",
                                "(DLjava/lang/Object;IJ)V", false);
                        } else {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkArrayWriteLong",
                                "(JLjava/lang/Object;IJ)V", false);
                        }
                        return; // write already done inside lock; skip super.visitInsn
                    }
                    Type valType = (opcode == Opcodes.DASTORE) ? Type.DOUBLE_TYPE : Type.LONG_TYPE;
                    int valueSlot = newLocal(valType);
                    mv.visitVarInsn(valType.getOpcode(Opcodes.ISTORE), valueSlot);
                    int indexSlot = newLocal(Type.INT_TYPE);
                    mv.visitVarInsn(Opcodes.ISTORE, indexSlot);
                    int arraySlot = newLocal(Type.getObjectType("java/lang/Object"));
                    mv.visitVarInsn(Opcodes.ASTORE, arraySlot);
                    mv.visitLdcInsn(eventType);
                    mv.visitVarInsn(Opcodes.ALOAD, arraySlot);
                    mv.visitVarInsn(Opcodes.ILOAD, indexSlot);
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod,
                            "(ILjava/lang/Object;IJ)V", false);
                    mv.visitVarInsn(Opcodes.ALOAD, arraySlot);
                    mv.visitVarInsn(Opcodes.ILOAD, indexSlot);
                    mv.visitVarInsn(valType.getOpcode(Opcodes.ILOAD), valueSlot);
                    super.visitInsn(opcode);
                    return;
                }
            } else if (isArrayLoad(opcode) || isArrayStore(opcode)) {
                String monitorMethod = isReplay ? "checkArray" : "logArray";
                int eventType = isArrayLoad(opcode) ? 7 : 8;
                String siteString = className + "." + methodName + "#" + instructionId++;
                long siteId = SyncTransformer.registerSiteId(siteString);

                if (isArrayLoad(opcode)) {
                    if (isReplay) {
                        if (opcode == Opcodes.AALOAD) {
                            // AALOAD returns a reference whose concrete type the JVM tracks via
                            // the array descriptor.  checkArrayReadObj() returns Object, which
                            // erases that type and causes a VerifyError when the result is used
                            // where a concrete subtype (e.g. Thread) is expected.
                            // Fix: coordinate the total-order turn with a void helper, then emit
                            // the real AALOAD directly so the verifier sees the concrete type.
                            mv.visitInsn(Opcodes.DUP2);   // [arrayRef, index, arrayRef, index]
                            mv.visitLdcInsn(siteId);       // [arrayRef, index, arrayRef, index, siteId]
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkArrayCoordinate",
                                "(Ljava/lang/Object;IJ)V", false); // [arrayRef, index]
                            mv.visitInsn(Opcodes.AALOAD);  // real load — element type preserved
                            return; // skip capture-mode block and super.visitInsn
                        } else {
                            // Stack: [arrayRef, index] — atomically read inside the coordination lock.
                            mv.visitLdcInsn(siteId);
                            if (opcode == Opcodes.FALOAD) {
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkArrayReadFloat",
                                    "(Ljava/lang/Object;IJ)F", false);
                            } else { // IALOAD, BALOAD, CALOAD, SALOAD
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkArrayReadInt",
                                    "(Ljava/lang/Object;IJ)I", false);
                            }
                            return; // read value is on stack; skip super.visitInsn
                        }
                    }
                    mv.visitInsn(Opcodes.DUP2); // [arrayRef, index, arrayRef, index]
                    mv.visitLdcInsn(eventType); // [arrayRef, index, arrayRef, index, type]
                    mv.visitInsn(Opcodes.DUP_X2); // [arrayRef, index, type, arrayRef, index, type]
                    mv.visitInsn(Opcodes.POP); // [arrayRef, index, type, arrayRef, index]
                    mv.visitLdcInsn(siteId); // [arrayRef, index, type, arrayRef, index, siteId]

                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod,
                            "(ILjava/lang/Object;IJ)V", false);
                } else {
                    if (isReplay) {
                        // Stack: [arrayRef, index, value(cat1)]
                        // Atomically write inside the coordination lock.
                        Type valType;
                        String checkMethod;
                        String checkDesc;
                        if (opcode == Opcodes.FASTORE) {
                            valType = Type.FLOAT_TYPE;
                            checkMethod = "checkArrayWriteFloat";
                            checkDesc = "(FLjava/lang/Object;IJ)V";
                        } else if (opcode == Opcodes.AASTORE) {
                            valType = Type.getObjectType("java/lang/Object");
                            checkMethod = "checkArrayWriteObj";
                            checkDesc = "(Ljava/lang/Object;Ljava/lang/Object;IJ)V";
                        } else { // IASTORE, BASTORE, CASTORE, SASTORE
                            valType = Type.INT_TYPE;
                            checkMethod = "checkArrayWriteInt";
                            checkDesc = "(ILjava/lang/Object;IJ)V";
                        }
                        int valueSlot = newLocal(valType);
                        mv.visitVarInsn(valType.getOpcode(Opcodes.ISTORE), valueSlot);
                        int indexSlot = newLocal(Type.INT_TYPE);
                        mv.visitVarInsn(Opcodes.ISTORE, indexSlot);
                        int arraySlot = newLocal(Type.getObjectType("java/lang/Object"));
                        mv.visitVarInsn(Opcodes.ASTORE, arraySlot);
                        mv.visitVarInsn(valType.getOpcode(Opcodes.ILOAD), valueSlot);
                        mv.visitVarInsn(Opcodes.ALOAD, arraySlot);
                        mv.visitVarInsn(Opcodes.ILOAD, indexSlot);
                        mv.visitLdcInsn(siteId);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, checkMethod, checkDesc, false);
                        return; // write already done inside lock; skip super.visitInsn
                    }
                    Type valType;
                    if (opcode == Opcodes.FASTORE) {
                        valType = Type.FLOAT_TYPE;
                    } else if (opcode == Opcodes.AASTORE) {
                        valType = Type.getObjectType("java/lang/Object");
                    } else {
                        valType = Type.INT_TYPE;
                    }
                    int valueSlot = newLocal(valType);
                    mv.visitVarInsn(valType.getOpcode(Opcodes.ISTORE), valueSlot);
                    int indexSlot = newLocal(Type.INT_TYPE);
                    mv.visitVarInsn(Opcodes.ISTORE, indexSlot);
                    int arraySlot = newLocal(Type.getObjectType("java/lang/Object"));
                    mv.visitVarInsn(Opcodes.ASTORE, arraySlot);
                    mv.visitLdcInsn(eventType);
                    mv.visitVarInsn(Opcodes.ALOAD, arraySlot);
                    mv.visitVarInsn(Opcodes.ILOAD, indexSlot);
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod,
                            "(ILjava/lang/Object;IJ)V", false);
                    mv.visitVarInsn(Opcodes.ALOAD, arraySlot);
                    mv.visitVarInsn(Opcodes.ILOAD, indexSlot);
                    mv.visitVarInsn(valType.getOpcode(Opcodes.ILOAD), valueSlot);
                    super.visitInsn(opcode);
                    return;
                }
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            // If we are in a constructor before the super/this() call, skip all
            // instrumentation.  Detect the super/this call itself and mark the guard.
            if (!superInitCalled) {
                if (opcode == Opcodes.INVOKESPECIAL && name.equals("<init>")) {
                    if (!pendingNewTypes.isEmpty() && pendingNewTypes.peek().equals(owner)) {
                        // This INVOKESPECIAL matches a pending NEW (e.g. "new Binding()" used as
                        // an argument to this()/super()).  Pop the tracked entry and pass through;
                        // we don't register the allocation because we're still pre-super/this.
                        pendingNewTypes.pop();
                        pendingNewSites.pop();
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        return;
                    }
                    // This is the super/this constructor call; emit it raw then lift the guard.
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    superInitCalled = true;
                    // Eagerly register 'this' before any field accesses or synchronized blocks
                    // in the constructor body can trigger lazy-registration with a different siteId.
                    // Without this, the first field write on 'this' inside the constructor
                    // lazy-registers it with the field-write siteId; if that per-role-site counter
                    // differs between runs (e.g. Derby4's LockOperator), replay diverges.
                    if (className.equals("org/codehaus/groovy/reflection/CachedClass")
                            && methodDescriptor.equals("(Ljava/lang/Class;Lorg/codehaus/groovy/reflection/ClassInfo;)V")) {
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitLdcInsn("groovy.CachedClass");
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "common/IdentityMapper",
                                "registerStableClassWrapper",
                                "(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)V", false);
                    } else if (className.equals("org/codehaus/groovy/reflection/ClassInfo")
                            && methodDescriptor.equals("(Lorg/codehaus/groovy/util/ManagedConcurrentMap$Segment;Ljava/lang/Class;I)V")) {
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitVarInsn(Opcodes.ALOAD, 2);
                        mv.visitLdcInsn("groovy.ClassInfo");
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "common/IdentityMapper",
                                "registerStableClassWrapper",
                                "(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)V", false);
                    } else {
                        String selfAllocSite = className + ".<init>#self_alloc";
                        long selfAllocSiteId = SyncTransformer.registerSiteId(selfAllocSite);
                        mv.visitVarInsn(Opcodes.ALOAD, 0);  // push 'this'
                        mv.visitLdcInsn(selfAllocSiteId);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "common/IdentityMapper", "registerAllocation",
                                "(Ljava/lang/Object;J)V", false);
                    }
                    return;
                }
                // Some other call before super() — pass through without instrumentation.
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                return;
            }

            // Detect the INVOKESPECIAL <init> that matches a preceding NEW instruction.
            // We must handle this before incrementing instructionId for the sync site string
            // so the allocation site and the constructor-call site remain distinct.
            if (opcode == Opcodes.INVOKESPECIAL && name.equals("<init>")
                    && !pendingNewTypes.isEmpty() && pendingNewTypes.peek().equals(owner)) {
                pendingNewTypes.pop();
                String allocSite = pendingNewSites.pop();
                long allocSiteId = SyncTransformer.registerSiteId(allocSite);
                // Execute the original constructor.
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                // After <init> returns (void), the initialized object is on top of the stack.
                // DUP it and register its allocation-time BirthId.
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(allocSiteId);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "common/IdentityMapper", "registerAllocation",
                        "(Ljava/lang/Object;J)V", false);
                return;
            }

            String siteString = className + "." + methodName + "#" + instructionId++;
            long siteId = SyncTransformer.registerSiteId(siteString);

            if (isReplay && (owner.equals("java/util/concurrent/locks/ReentrantLock")
                    || owner.equals("java/util/concurrent/locks/Lock"))) {
                if (name.equals("lock") && descriptor.equals("()V")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replayLock",
                            "(Ljava/util/concurrent/locks/Lock;J)V", false);
                    return;
                } else if (name.equals("unlock") && descriptor.equals("()V")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replayUnlock",
                            "(Ljava/util/concurrent/locks/Lock;J)V", false);
                    return;
                } else if (name.equals("newCondition") && descriptor.equals("()Ljava/util/concurrent/locks/Condition;")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replayNewCondition",
                            "(Ljava/util/concurrent/locks/Lock;J)Ljava/util/concurrent/locks/Condition;", false);
                    return;
                }
            }

            if (!isReplay && (owner.equals("java/util/concurrent/locks/ReentrantLock")
                    || owner.equals("java/util/concurrent/locks/Lock"))) {
                if (name.equals("newCondition") && descriptor.equals("()Ljava/util/concurrent/locks/Condition;")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "captureNewCondition",
                            "(Ljava/util/concurrent/locks/Lock;J)Ljava/util/concurrent/locks/Condition;", false);
                    return;
                }
            }

            if (isReplay && owner.equals("java/util/concurrent/locks/Condition")) {
                if (name.equals("await") && descriptor.equals("()V")) {
                    String wakeupSite0 = className + "." + methodName + "#" + instructionId++;
                    long wakeupSiteId0 = SyncTransformer.registerSiteId(wakeupSite0);
                    mv.visitLdcInsn(siteId);
                    mv.visitLdcInsn(wakeupSiteId0);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replayAwait",
                            "(Ljava/util/concurrent/locks/Condition;JJ)V", false);
                    return;
                } else if (name.equals("awaitUninterruptibly") && descriptor.equals("()V")) {
                    String wakeupSite1 = className + "." + methodName + "#" + instructionId++;
                    long wakeupSiteId1 = SyncTransformer.registerSiteId(wakeupSite1);
                    mv.visitLdcInsn(siteId);
                    mv.visitLdcInsn(wakeupSiteId1);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replayAwaitUninterruptibly",
                            "(Ljava/util/concurrent/locks/Condition;JJ)V", false);
                    return;
                } else if (name.equals("awaitNanos") && descriptor.equals("(J)J")) {
                    String wakeupSite2 = className + "." + methodName + "#" + instructionId++;
                    long wakeupSiteId2 = SyncTransformer.registerSiteId(wakeupSite2);
                    mv.visitLdcInsn(siteId);
                    mv.visitLdcInsn(wakeupSiteId2);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replayAwaitNanos",
                            "(Ljava/util/concurrent/locks/Condition;JJJ)J", false);
                    return;
                } else if (name.equals("awaitUntil") && descriptor.equals("(Ljava/util/Date;)Z")) {
                    String wakeupSite3 = className + "." + methodName + "#" + instructionId++;
                    long wakeupSiteId3 = SyncTransformer.registerSiteId(wakeupSite3);
                    mv.visitLdcInsn(siteId);
                    mv.visitLdcInsn(wakeupSiteId3);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replayAwaitUntil",
                            "(Ljava/util/concurrent/locks/Condition;Ljava/util/Date;JJ)Z", false);
                    return;
                } else if (name.equals("await") && descriptor.equals("(JLjava/util/concurrent/TimeUnit;)Z")) {
                    String wakeupSite4 = className + "." + methodName + "#" + instructionId++;
                    long wakeupSiteId4 = SyncTransformer.registerSiteId(wakeupSite4);
                    mv.visitLdcInsn(siteId);
                    mv.visitLdcInsn(wakeupSiteId4);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replayAwaitTimed",
                            "(Ljava/util/concurrent/locks/Condition;JLjava/util/concurrent/TimeUnit;JJ)Z", false);
                    return;
                } else if (name.equals("signal") && descriptor.equals("()V")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replaySignal",
                            "(Ljava/util/concurrent/locks/Condition;J)V", false);
                    return;
                } else if (name.equals("signalAll") && descriptor.equals("()V")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replaySignalAll",
                            "(Ljava/util/concurrent/locks/Condition;J)V", false);
                    return;
                }
            }

            if (isReplay && owner.equals("java/lang/Object")) {
                if (name.equals("wait") && descriptor.equals("()V")) {
                    String wakeupSite = className + "." + methodName + "#" + instructionId++;
                    long wakeupSiteId = SyncTransformer.registerSiteId(wakeupSite);
                    mv.visitLdcInsn(siteId);
                    mv.visitLdcInsn(wakeupSiteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replayObjectWait",
                            "(Ljava/lang/Object;JJ)V", false);
                    return;
                } else if (name.equals("wait") && descriptor.equals("(J)V")) {
                    String wakeupSite = className + "." + methodName + "#" + instructionId++;
                    long wakeupSiteId = SyncTransformer.registerSiteId(wakeupSite);
                    mv.visitLdcInsn(siteId);
                    mv.visitLdcInsn(wakeupSiteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replayObjectWait",
                            "(Ljava/lang/Object;JJJ)V", false);
                    return;
                } else if (name.equals("wait") && descriptor.equals("(JI)V")) {
                    String wakeupSite = className + "." + methodName + "#" + instructionId++;
                    long wakeupSiteId = SyncTransformer.registerSiteId(wakeupSite);
                    mv.visitLdcInsn(siteId);
                    mv.visitLdcInsn(wakeupSiteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replayObjectWait",
                            "(Ljava/lang/Object;JIJJ)V", false);
                    return;
                } else if (name.equals("notify") && descriptor.equals("()V")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replayObjectNotify",
                            "(Ljava/lang/Object;J)V", false);
                    return;
                } else if (name.equals("notifyAll") && descriptor.equals("()V")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replayObjectNotifyAll",
                            "(Ljava/lang/Object;J)V", false);
                    return;
                }
            }

            if (owner.equals("java/util/concurrent/locks/LockSupport")) {
                if (name.equals("park")) {
                    if (descriptor.equals("(Ljava/lang/Object;)V")) {
                        mv.visitInsn(Opcodes.DUP);
                    } else {
                        mv.visitInsn(Opcodes.ACONST_NULL);
                    }
                    logSyncCall(3, siteId); // THREAD_PARK
                } else if (name.equals("parkNanos") || name.equals("parkUntil")) {
                    if (descriptor.startsWith("(Ljava/lang/Object;")) {
                        // Stack: [blocker, long] — extract blocker copy from under the long
                        mv.visitInsn(Opcodes.DUP2_X1); // [long, blocker, long]
                        mv.visitInsn(Opcodes.POP2); // [long, blocker]
                        mv.visitInsn(Opcodes.DUP); // [long, blocker, blocker_copy]
                        logSyncCall(3, siteId); // consumes blocker_copy → [long, blocker]
                        // Restore original stack order: [blocker, long]
                        mv.visitInsn(Opcodes.DUP_X2); // [blocker, long, blocker]
                        mv.visitInsn(Opcodes.POP); // [blocker, long]
                    } else {
                        // Stack: [long] — no blocker
                        mv.visitInsn(Opcodes.ACONST_NULL);
                        logSyncCall(3, siteId); // THREAD_PARK
                    }
                } else if (name.equals("unpark")) {
                    mv.visitInsn(Opcodes.DUP);
                    logSyncCall(4, siteId); // THREAD_UNPARK
                }
            } else if (name.equals("start") && descriptor.equals("()V")) {
                String monitorMethod = isReplay ? "checkThreadStart" : "logThreadStart";
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(siteId);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod,
                        "(Ljava/lang/Object;J)V", false);
            } else if (owner.equals("java/util/Vector") && !shouldSkipCollectionTraffic()) {
                if (name.equals("size") && descriptor.equals("()I")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "vectorSize",
                            "(Ljava/util/Vector;J)I", false);
                    return;
                } else if (name.equals("elementAt") && descriptor.equals("(I)Ljava/lang/Object;")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "vectorElementAt",
                            "(Ljava/util/Vector;IJ)Ljava/lang/Object;", false);
                    return;
                } else if (name.equals("removeAllElements") && descriptor.equals("()V")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "vectorRemoveAllElements",
                            "(Ljava/util/Vector;J)V", false);
                    return;
                }
            } else if (owner.equals("java/util/Map") && !shouldSkipCollectionTraffic()) {
                if (name.equals("keySet") && descriptor.equals("()Ljava/util/Set;")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "mapKeySet",
                            "(Ljava/util/Map;J)Ljava/util/Set;", false);
                    return;
                } else if (name.equals("entrySet") && descriptor.equals("()Ljava/util/Set;")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "mapEntrySet",
                            "(Ljava/util/Map;J)Ljava/util/Set;", false);
                    return;
                } else if (name.equals("put") && descriptor.equals("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "mapPut",
                            "(Ljava/util/Map;Ljava/lang/Object;Ljava/lang/Object;J)Ljava/lang/Object;", false);
                    return;
                } else if (name.equals("remove") && descriptor.equals("(Ljava/lang/Object;)Ljava/lang/Object;")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "mapRemove",
                            "(Ljava/util/Map;Ljava/lang/Object;J)Ljava/lang/Object;", false);
                    return;
                } else if (name.equals("clear") && descriptor.equals("()V")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "mapClear",
                            "(Ljava/util/Map;J)V", false);
                    return;
                }
            } else if (owner.equals("java/util/Set") && !shouldSkipCollectionTraffic()) {
                if (name.equals("iterator") && descriptor.equals("()Ljava/util/Iterator;")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "setIterator",
                            "(Ljava/util/Set;J)Ljava/util/Iterator;", false);
                    return;
                }
            } else if (owner.equals("java/util/Iterator") && !shouldSkipCollectionTraffic()) {
                if (name.equals("hasNext") && descriptor.equals("()Z")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "iteratorHasNext",
                            "(Ljava/util/Iterator;J)Z", false);
                    return;
                } else if (name.equals("next") && descriptor.equals("()Ljava/lang/Object;")) {
                    mv.visitLdcInsn(siteId);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "iteratorNext",
                            "(Ljava/util/Iterator;J)Ljava/lang/Object;", false);
                    return;
                }
            } else if (owner.equals("java/lang/Thread")) {
                if (name.equals("join")) {
                    // Determine if it is a timed join or infinite join
                    int eventType = descriptor.equals("()V") ? 10 : 18; // 10=JOIN, 18=JOIN_TIMEOUT

                    logReceiverPreservingInvokeArgs(eventType, siteId, descriptor);
                } else if (name.equals("sleep")) {
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    logSyncCall(12, siteId); // THREAD_SLEEP
                } else if (name.equals("yield")) {
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    logSyncCall(14, siteId); // THREAD_YIELD
                } else if (name.equals("interrupt")) {
                    mv.visitInsn(Opcodes.DUP);
                    logSyncCall(11, siteId); // THREAD_INTERRUPT
                } else if (name.equals("interrupted")) {
                    // Static method — push current thread as the lock to identify
                    // which thread's interrupt status was checked (detection side of HB)
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread",
                            "()Ljava/lang/Thread;", false);
                    logSyncCall(19, siteId); // THREAD_INTERRUPT_CHECK
                } else if (name.equals("isInterrupted")) {
                    // Instance method — receiver is the thread being checked
                    mv.visitInsn(Opcodes.DUP);
                    logSyncCall(19, siteId); // THREAD_INTERRUPT_CHECK
                }
            } else if (owner.equals("java/lang/Object")) {
                if (name.equals("wait")) {
                    logReceiverPreservingInvokeArgs(15, siteId, descriptor); // THREAD_WAIT
                } else if (name.equals("notify") || name.equals("notifyAll")) {
                    mv.visitInsn(Opcodes.DUP);
                    logSyncCall(name.equals("notify") ? 16 : 17, siteId);
                }
            } else if (owner.equals("java/util/concurrent/locks/ReentrantLock")
                    || owner.equals("java/util/concurrent/locks/Lock")) {
                if (name.equals("lock")) {
                    // Log MONITOR_ENTER AFTER the actual lock.lock() so the trace
                    // reflects when the lock is held, not when the thread started waiting.
                    // Without this, replay deadlocks: the coordinator advances past
                    // MONITOR_ENTER expecting the next event, but the thread is still
                    // blocked in lock.lock() because another thread holds the lock.
                    int lockSlot = newLocal(Type.getObjectType("java/lang/Object"));
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitVarInsn(Opcodes.ASTORE, lockSlot);
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    mv.visitVarInsn(Opcodes.ALOAD, lockSlot);
                    logSyncCall(1, siteId); // MONITOR_ENTER, logged after lock is actually held
                    return;
                } else if (name.equals("unlock")) {
                    mv.visitInsn(Opcodes.DUP);
                    logSyncCall(2, siteId); // MONITOR_EXIT
                }
            } else if (owner.equals("java/util/concurrent/locks/Condition")) {
                if (name.startsWith("await")) {
                    // Pop any args into locals so the receiver is on top for DUP.
                    Type[] argTypes = Type.getArgumentTypes(descriptor);
                    int[] argSlots = new int[argTypes.length];
                    for (int i = argTypes.length - 1; i >= 0; i--) {
                        argSlots[i] = newLocal(argTypes[i]);
                        mv.visitVarInsn(argTypes[i].getOpcode(Opcodes.ISTORE), argSlots[i]);
                    }
                    mv.visitInsn(Opcodes.DUP);
                    logSyncCall(15, siteId); // THREAD_WAIT
                    for (int i = 0; i < argTypes.length; i++) {
                        mv.visitVarInsn(argTypes[i].getOpcode(Opcodes.ILOAD), argSlots[i]);
                    }
                } else if (name.equals("signal")) {
                    mv.visitInsn(Opcodes.DUP);
                    logSyncCall(16, siteId); // THREAD_NOTIFY
                } else if (name.equals("signalAll")) {
                    mv.visitInsn(Opcodes.DUP);
                    logSyncCall(17, siteId); // THREAD_NOTIFY_ALL
                }
            }

            // Detect atomic classes — save receiver + args BEFORE the call,
            // execute the call, then log with full context (WHERE + WHAT).
            if (owner.startsWith("java/util/concurrent/atomic/Atomic")) {
                if (shouldSkipAtomicTraffic()) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    return;
                }
                int atomicEventType = classifyAtomicOp(name);
                if (atomicEventType != -1) {
                    boolean isArrayAtomic = isAtomicArrayClass(owner);
                    Type[] argTypes = Type.getArgumentTypes(descriptor);
                    char returnTypeChar = getReturnType(descriptor);

                    // 1. Pop all arguments into local variables (top-of-stack = last arg first)
                    int[] argSlots = new int[argTypes.length];
                    for (int i = argTypes.length - 1; i >= 0; i--) {
                        argSlots[i] = newLocal(argTypes[i]);
                        mv.visitVarInsn(argTypes[i].getOpcode(Opcodes.ISTORE), argSlots[i]);
                    }
                    // Pop receiver into a local
                    int receiverSlot = newLocal(Type.getObjectType("java/lang/Object"));
                    mv.visitVarInsn(Opcodes.ASTORE, receiverSlot);

                    if (isReplay) {
                        if (atomicEventType == common.BinarySchema.Event.ATOMIC_CAS) {
                            // CAS: inject captured result + force-set post-op state.
                            // The original call is suppressed entirely.
                            mv.visitVarInsn(Opcodes.ALOAD, receiverSlot);
                            if (isArrayAtomic && argTypes.length > 0) {
                                mv.visitVarInsn(Opcodes.ILOAD, argSlots[0]);
                            } else {
                                mv.visitLdcInsn(-1);
                            }
                            mv.visitLdcInsn(siteId);
                            emitAtomicCasCheckCall(returnTypeChar);
                        } else {
                            // Non-CAS: execute natively within controlLock.
                            // Total order guarantees the result equals the captured value.

                            // 1. beginAtomicReplay(receiver, index, eventType, siteId) —
                            //    waits for turn and holds controlLock.
                            mv.visitVarInsn(Opcodes.ALOAD, receiverSlot);
                            if (isArrayAtomic && argTypes.length > 0) {
                                mv.visitVarInsn(Opcodes.ILOAD, argSlots[0]);
                            } else {
                                mv.visitLdcInsn(-1);
                            }
                            mv.visitLdcInsn(atomicEventType);
                            mv.visitLdcInsn(siteId);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass,
                                    "beginAtomicReplay", "(Ljava/lang/Object;IIJ)V", false);

                            // 2. Execute the original atomic call (lock is held).
                            mv.visitVarInsn(Opcodes.ALOAD, receiverSlot);
                            for (int i = 0; i < argTypes.length; i++) {
                                mv.visitVarInsn(argTypes[i].getOpcode(Opcodes.ILOAD), argSlots[i]);
                            }
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

                            // 3. endAtomicReplay() — advances currentIdx, signals, releases lock.
                            //    For void calls the stack is empty; for value calls the return value
                            //    sits below this void call and is left in place as the result.
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass,
                                    "endAtomicReplay", "()V", false);
                        }
                        return;
                    } else {

                        // 2. Acquire captureOrderLock before the actual atomic call so
                        //    the lock is already held when the operation executes.
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass,
                                "beginAtomicCapture", "()V", false);

                        // 3. Restore the stack and execute the original atomic call
                        //    (lock held across this call).
                        mv.visitVarInsn(Opcodes.ALOAD, receiverSlot);
                        for (int i = 0; i < argTypes.length; i++) {
                            mv.visitVarInsn(argTypes[i].getOpcode(Opcodes.ILOAD), argSlots[i]);
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

                        // 4. End the capture: assigns seq, writes trace record, releases lock.
                        if (returnTypeChar == 'V') {
                            int valueArgIdx = argTypes.length - 1;
                            Type valueType = argTypes[valueArgIdx];

                            mv.visitVarInsn(valueType.getOpcode(Opcodes.ILOAD), argSlots[valueArgIdx]);
                            mv.visitVarInsn(Opcodes.ALOAD, receiverSlot);
                            if (isArrayAtomic) {
                                mv.visitVarInsn(Opcodes.ILOAD, argSlots[0]);
                            } else {
                                mv.visitLdcInsn(-1);
                            }
                            mv.visitLdcInsn(atomicEventType);
                            mv.visitLdcInsn(siteId);
                            emitAtomicEndCaptureCall(valueType);
                        } else {
                            if (returnTypeChar == 'J' || returnTypeChar == 'D') {
                                mv.visitInsn(Opcodes.DUP2);
                            } else {
                                mv.visitInsn(Opcodes.DUP);
                            }
                            mv.visitVarInsn(Opcodes.ALOAD, receiverSlot);
                            if (isArrayAtomic && argTypes.length > 0) {
                                mv.visitVarInsn(Opcodes.ILOAD, argSlots[0]);
                            } else {
                                mv.visitLdcInsn(-1);
                            }
                            mv.visitLdcInsn(atomicEventType);
                            mv.visitLdcInsn(siteId);
                            emitAtomicEndCaptureCall(returnTypeChar);
                        }
                        return;
                    }
                }
            }

            // Detect nondeterministic calls (Random, System time, Math.random).
            // Capture: execute the real call then log the return value.
            // Replay: skip the real call and return the captured value.
            String nondetType = classifyNondetOp(owner, name);
            if (nondetType != null) {
                Type[] argTypes = Type.getArgumentTypes(descriptor);
                boolean isStaticCall = (opcode == Opcodes.INVOKESTATIC);

                if (isReplay) {
                    // Pop all args (top to bottom), then pop receiver if instance method
                    for (int i = argTypes.length - 1; i >= 0; i--) {
                        mv.visitInsn(argTypes[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
                    }
                    if (!isStaticCall) {
                        mv.visitInsn(Opcodes.POP); // pop receiver
                    }
                    mv.visitLdcInsn(siteId);
                    emitNondetReplayCall(nondetType);
                } else {
                    // Save args and receiver (for instance methods) before the call
                    int[] argSlots = new int[argTypes.length];
                    for (int i = argTypes.length - 1; i >= 0; i--) {
                        argSlots[i] = newLocal(argTypes[i]);
                        mv.visitVarInsn(argTypes[i].getOpcode(Opcodes.ISTORE), argSlots[i]);
                    }
                    int receiverSlot = -1;
                    if (!isStaticCall) {
                        receiverSlot = newLocal(Type.getObjectType("java/lang/Object"));
                        mv.visitVarInsn(Opcodes.ASTORE, receiverSlot);
                        mv.visitVarInsn(Opcodes.ALOAD, receiverSlot);
                    }
                    for (int i = 0; i < argTypes.length; i++) {
                        mv.visitVarInsn(argTypes[i].getOpcode(Opcodes.ILOAD), argSlots[i]);
                    }
                    // Execute the actual (nondeterministic) call
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    // DUP return value so we can log it while leaving it on the stack
                    boolean isWide = nondetType.equals("LONG") || nondetType.equals("DOUBLE");
                    mv.visitInsn(isWide ? Opcodes.DUP2 : Opcodes.DUP);
                    mv.visitLdcInsn(siteId);
                    emitNondetLogCall(nondetType);
                }
                return;
            }

            // Execute the original method call
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (isBlockingCall(owner, name)) {
                String wakeupSite = className + "." + methodName + "#" + instructionId++;
                long wakeupSiteId = SyncTransformer.registerSiteId(wakeupSite);
                mv.visitInsn(Opcodes.ACONST_NULL);
                logSyncCall(13, wakeupSiteId); // THREAD_WAKEUP
            }
        }

        // Helper to keep the code clean and ensure the stack [eventType, object, site]
        // is correct
        private void logSyncCall(int type, long siteId) {
            String monitorMethod = isReplay ? "checkSync" : "logSync";
            mv.visitLdcInsn(type);
            mv.visitInsn(Opcodes.SWAP);
            mv.visitLdcInsn(siteId);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod, monitorDescriptor, false);
        }

        private void logReceiverPreservingInvokeArgs(int type, long siteId, String descriptor) {
            Type[] args = Type.getArgumentTypes(descriptor);
            int[] argSlots = new int[args.length];
            for (int i = args.length - 1; i >= 0; i--) {
                argSlots[i] = newLocal(args[i]);
                mv.visitVarInsn(args[i].getOpcode(Opcodes.ISTORE), argSlots[i]);
            }

            int receiverSlot = newLocal(Type.getObjectType("java/lang/Object"));
            mv.visitVarInsn(Opcodes.ASTORE, receiverSlot);

            mv.visitVarInsn(Opcodes.ALOAD, receiverSlot);
            logSyncCall(type, siteId);

            mv.visitVarInsn(Opcodes.ALOAD, receiverSlot);
            for (int i = 0; i < args.length; i++) {
                mv.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), argSlots[i]);
            }
        }

        /** Capture mode: ends the atomic bracket — seq assigned, trace written, lock released. */
        private void emitAtomicEndCaptureCall(Type valueType) {
            int sort = valueType.getSort();
            if (sort == Type.LONG) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "endAtomicCaptureLong",
                        "(JLjava/lang/Object;IIJ)V", false);
            } else if (sort == Type.OBJECT || sort == Type.ARRAY) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "endAtomicCaptureObj",
                        "(Ljava/lang/Object;Ljava/lang/Object;IIJ)V", false);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "endAtomicCaptureInt",
                        "(ILjava/lang/Object;IIJ)V", false);
            }
        }

        private void emitAtomicEndCaptureCall(char returnTypeChar) {
            if (returnTypeChar == 'J' || returnTypeChar == 'D') {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "endAtomicCaptureLong",
                        "(JLjava/lang/Object;IIJ)V", false);
            } else if (returnTypeChar == 'L' || returnTypeChar == '[') {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "endAtomicCaptureObj",
                        "(Ljava/lang/Object;Ljava/lang/Object;IIJ)V", false);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "endAtomicCaptureInt",
                        "(ILjava/lang/Object;IIJ)V", false);
            }
        }

        /** Replay mode, CAS path: injects captured result from trace. Stack before: [receiver, index, siteId]. */
        private void emitAtomicCasCheckCall(char returnTypeChar) {
            if (returnTypeChar == 'J' || returnTypeChar == 'D') {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkAtomicCasLong",
                        "(Ljava/lang/Object;IJ)J", false);
            } else if (returnTypeChar == 'L' || returnTypeChar == '[') {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkAtomicCasObj",
                        "(Ljava/lang/Object;IJ)Ljava/lang/Object;", false);
            } else {
                // Covers 'Z' (compareAndSet → boolean), 'I' (compareAndExchange → int), etc.
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkAtomicCasInt",
                        "(Ljava/lang/Object;IJ)I", false);
            }
        }

        /** Emits the appropriate checkFieldRead* call for the given field descriptor. */
        private void emitFieldReadCheckCall(String descriptor) {
            // Stack before call: [owner, siteId, isVolatile, isStatic, fieldName, ownerName]
            if (descriptor.equals("J")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldReadLong",
                    "(Ljava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)J", false);
            } else if (descriptor.equals("D")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldReadDouble",
                    "(Ljava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)D", false);
            } else if (descriptor.equals("F")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldReadFloat",
                    "(Ljava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)F", false);
            } else if (descriptor.startsWith("L") || descriptor.startsWith("[")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldReadObj",
                    "(Ljava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
                // CHECKCAST so the verifier sees the concrete type, not just Object.
                String castTarget = descriptor.startsWith("L")
                    ? descriptor.substring(1, descriptor.length() - 1)
                    : descriptor; // array descriptors are passed as-is
                mv.visitTypeInsn(Opcodes.CHECKCAST, castTarget);
            } else {
                // I, Z, B, S, C
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldReadInt",
                    "(Ljava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)I", false);
            }
        }

        /** Emits the appropriate checkFieldWrite* call for the given field descriptor. */
        private void emitFieldWriteCheckCall(String descriptor) {
            if (descriptor.equals("J")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldWriteLong",
                    "(JLjava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)V", false);
            } else if (descriptor.equals("D")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldWriteDouble",
                    "(DLjava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)V", false);
            } else if (descriptor.equals("F")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldWriteFloat",
                    "(FLjava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)V", false);
            } else if (descriptor.startsWith("L") || descriptor.startsWith("[")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldWriteObj",
                    "(Ljava/lang/Object;Ljava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)V", false);
            } else {
                // I, Z, B, S, C
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldWriteInt",
                    "(ILjava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)V", false);
            }
        }

        /** Emits the appropriate logFieldRead* call for the given field descriptor (capture mode). */
        private void emitFieldReadLogCall(String descriptor) {
            if (descriptor.equals("J")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldReadLong",
                    "(Ljava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)J", false);
            } else if (descriptor.equals("D")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldReadDouble",
                    "(Ljava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)D", false);
            } else if (descriptor.equals("F")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldReadFloat",
                    "(Ljava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)F", false);
            } else if (descriptor.startsWith("L") || descriptor.startsWith("[")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldReadObj",
                    "(Ljava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
                String castTarget = descriptor.startsWith("L")
                    ? descriptor.substring(1, descriptor.length() - 1)
                    : descriptor;
                mv.visitTypeInsn(Opcodes.CHECKCAST, castTarget);
            } else {
                // I, Z, B, S, C
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldReadInt",
                    "(Ljava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)I", false);
            }
        }

        /** Emits the appropriate logFieldWrite* call for the given field descriptor (capture mode). */
        private void emitFieldWriteLogCall(String descriptor) {
            if (descriptor.equals("J")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldWriteLong",
                    "(JLjava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)V", false);
            } else if (descriptor.equals("D")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldWriteDouble",
                    "(DLjava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)V", false);
            } else if (descriptor.equals("F")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldWriteFloat",
                    "(FLjava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)V", false);
            } else if (descriptor.startsWith("L") || descriptor.startsWith("[")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldWriteObj",
                    "(Ljava/lang/Object;Ljava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)V", false);
            } else {
                // I, Z, B, S, C
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldWriteInt",
                    "(ILjava/lang/Object;JZZLjava/lang/String;Ljava/lang/String;)V", false);
            }
        }

        /**
         * Returns the nondeterminism category for a method call, or null if not nondeterministic.
         * Categories: "INT" (int/boolean), "FLOAT", "LONG" (long), "DOUBLE"
         */
        private static String classifyNondetOp(String owner, String name) {
            switch (owner) {
                case "java/util/Random":
                case "java/security/SecureRandom":
                case "java/util/concurrent/ThreadLocalRandom":
                    switch (name) {
                        case "nextInt":     return "INT";
                        case "nextLong":    return "LONG";
                        case "nextFloat":   return "FLOAT";
                        case "nextDouble":  return "DOUBLE";
                        case "nextBoolean": return "INT";
                        case "nextGaussian":return "DOUBLE";
                    }
                    break;
                case "java/lang/System":
                    if (name.equals("currentTimeMillis") || name.equals("nanoTime")) return "LONG";
                    break;
                case "java/lang/Math":
                    if (name.equals("random")) return "DOUBLE";
                    break;
            }
            return null;
        }

        /** Emits a call to logNondet*(value, siteId) in CaptureMonitor. Stack: [..., value, siteId] → [...] */
        private void emitNondetLogCall(String nondetType) {
            switch (nondetType) {
                case "INT":
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logNondetInt", "(IJ)V", false);
                    break;
                case "FLOAT":
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logNondetFloat", "(FJ)V", false);
                    break;
                case "LONG":
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logNondetLong", "(JJ)V", false);
                    break;
                case "DOUBLE":
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logNondetDouble", "(DJ)V", false);
                    break;
            }
        }

        /** Emits a call to replayNondet*(siteId) in ReplayMonitor. Stack: [..., siteId] → [..., value] */
        private void emitNondetReplayCall(String nondetType) {
            switch (nondetType) {
                case "INT":
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replayNondetInt", "(J)I", false);
                    break;
                case "FLOAT":
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replayNondetFloat", "(J)F", false);
                    break;
                case "LONG":
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replayNondetLong", "(J)J", false);
                    break;
                case "DOUBLE":
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "replayNondetDouble", "(J)D", false);
                    break;
            }
        }

        private boolean isBlockingCall(String owner, String name) {
            return (owner.equals("java/lang/Thread") && (name.equals("join") || name.equals("sleep"))) ||
                    (owner.equals("java/lang/Object") && name.equals("wait")) ||
                    (owner.equals("java/util/concurrent/locks/LockSupport") &&
                            (name.equals("park") || name.equals("parkNanos") || name.equals("parkUntil"))) ||
                    (owner.equals("java/util/concurrent/locks/Condition") && name.startsWith("await"));
        }

        // Classify atomic method name → event type, or -1 to skip non-atomic methods
        private static int classifyAtomicOp(String name) {
            // Skip non-atomic methods that are by default used in the atomic class
            if (name.equals("<init>") || name.equals("toString") || name.equals("hashCode") ||
                    name.equals("intValue") || name.equals("longValue") || name.equals("floatValue") ||
                    name.equals("doubleValue") || name.equals("length") || name.equals("newUpdater"))
                return -1;
            // CAS — must be checked before general RMW: result is injected during replay
            // because it determines control flow and reference identity can differ across runs.
            if (name.startsWith("compareAndSet") || name.startsWith("compareAndExchange") ||
                    name.startsWith("weakCompareAndSet") || name.startsWith("weakCompareAndExchange"))
                return common.BinarySchema.Event.ATOMIC_CAS; // 26
            // Writes
            if (name.startsWith("set") || name.equals("lazySet"))
                return common.BinarySchema.Event.ATOMIC_WRITE; // 21
            // Reads (get without "And")
            if (name.equals("get") || name.equals("getPlain") || name.equals("getOpaque") ||
                    name.equals("getAcquire") || name.equals("getReference") || name.equals("getStamp") ||
                    name.equals("isMarked"))
                return common.BinarySchema.Event.ATOMIC_READ; // 20
            // Everything else is read-modify-write
            return common.BinarySchema.Event.ATOMIC_RMW; // 22
        }

        // Parse return type from descriptor: "(III)Z" → 'Z', "()V" → 'V',
        // "()Ljava/lang/Object;" → 'L'
        private static char getReturnType(String descriptor) {
            return descriptor.charAt(descriptor.indexOf(')') + 1);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (!superInitCalled) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            if (SyncTransformer.shouldSkipLuceneBootstrapTraffic(className, methodName)) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            if (SyncTransformer.shouldSkipLucene1544SetupTraffic(className, methodName)) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            if (SyncTransformer.shouldSkipGroovyServletTraffic(className)) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            if (SyncTransformer.shouldSkipGroovyServletInitFieldTraffic(className, methodName)) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            if (SyncTransformer.shouldSkipDependencyFieldTraffic(className, owner)) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            if (SyncTransformer.shouldSkipBootstrapFieldTraffic(className, owner)) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            if (SyncTransformer.shouldSkipGeneratedScriptFieldTraffic(className, sourceFile, owner)) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            // Skip compiler-generated synthetic fields (e.g. $assertionsDisabled) and
            // all final fields. Java 21 forbids writing final fields via reflection, so
            // routing them through the monitor crashes class init. Final fields also
            // can't race, so there's no value in tracking them.
            if (name.startsWith("$")) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            if (SyncTransformer.isFieldFinal(loader, owner, name)) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            // Resolve volatility from the OWNER class, not just the current class.
            // This handles cross-class field accesses (e.g., Main accessing Counter.x).
            boolean isVolatile = SyncTransformer.isFieldVolatile(loader, owner, name);
            // Check if we need the IS_STATIC flag
            boolean isStatic = (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC);

            String siteString = className + "." + methodName + "#" + instructionId++;
            long siteId = SyncTransformer.registerSiteId(siteString);

            // Field READs: the typed method performs the actual load and the seq
            // assignment atomically inside a lock (captureLock in capture mode,
            // controlLock in replay mode), so the recorded seq faithfully reflects
            // the real read order.  The original GETFIELD/GETSTATIC is skipped.
            if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) {
                if (opcode == Opcodes.GETSTATIC) {
                    // Stack: [] — push null as owner placeholder
                    mv.visitInsn(Opcodes.ACONST_NULL);
                } // GETFIELD: Stack: [objectRef] — consumed directly by the call
                mv.visitLdcInsn(siteId);
                mv.visitInsn(isVolatile ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                mv.visitLdcInsn(isStatic ? 1 : 0);
                mv.visitLdcInsn(name);
                mv.visitLdcInsn(owner);
                if (isReplay) emitFieldReadCheckCall(descriptor);
                else          emitFieldReadLogCall(descriptor);
                return;
            }

            // Field WRITEs: same principle — typed method holds the lock across the
            // actual store and the seq assignment.  Original PUTFIELD/PUTSTATIC skipped.
            if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) {
                Type valType = Type.getType(descriptor);
                if (opcode == Opcodes.PUTSTATIC) {
                    // Stack: [value]
                    int valueSlot = newLocal(valType);
                    mv.visitVarInsn(valType.getOpcode(Opcodes.ISTORE), valueSlot);
                    mv.visitVarInsn(valType.getOpcode(Opcodes.ILOAD), valueSlot);
                    mv.visitInsn(Opcodes.ACONST_NULL); // no owner for static
                } else { // PUTFIELD — Stack: [objectRef, value]
                    int valueSlot = newLocal(valType);
                    mv.visitVarInsn(valType.getOpcode(Opcodes.ISTORE), valueSlot);
                    int ownerSlot = newLocal(Type.getObjectType("java/lang/Object"));
                    mv.visitVarInsn(Opcodes.ASTORE, ownerSlot);
                    mv.visitVarInsn(valType.getOpcode(Opcodes.ILOAD), valueSlot);
                    mv.visitVarInsn(Opcodes.ALOAD, ownerSlot);
                }
                // Stack: [value, owner]
                mv.visitLdcInsn(siteId);
                mv.visitInsn(isVolatile ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                mv.visitLdcInsn(isStatic ? 1 : 0);
                mv.visitLdcInsn(name);
                mv.visitLdcInsn(owner);
                if (isReplay) emitFieldWriteCheckCall(descriptor);
                else          emitFieldWriteLogCall(descriptor);
                return;
            }

        }
    }

    /**
     * Instruments class initializers (<clinit>) to log CLASS_INIT_BEGIN and
     * CLASS_INIT_END
     * as special synchronization events. Wrapped around the normal
     * SyncMethodVisitor so that
     * other instrumentation (fields, arrays, etc.) still applies.
     */
    static class ClinitMethodVisitor extends MethodVisitor {
        private final String className;
        private final String monitorClass;
        private final String monitorMethod;

        public ClinitMethodVisitor(MethodVisitor mv, String className,
                                String monitorClass, String monitorMethod) {
            super(Opcodes.ASM9, mv);
            this.className = className;
            this.monitorClass = monitorClass;
            this.monitorMethod = monitorMethod;
        }

        @Override
        public void visitCode() {
            super.visitCode();

            // Log CLASS_INIT_BEGIN at the start of <clinit>
            String beginSite = className + ".<clinit>#begin";
            long beginSiteId = SyncTransformer.registerSiteId(beginSite);
            mv.visitLdcInsn(23); // BinarySchema.Event.CLASS_INIT_BEGIN
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitLdcInsn(beginSiteId);
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    monitorClass,
                    monitorMethod,
                    "(ILjava/lang/Object;J)V",
                    false);
        }

        @Override
        public void visitInsn(int opcode) {
            // For normal returns and throws, log CLASS_INIT_END before exiting
            if (opcode == Opcodes.RETURN) {
                String endSite = className + ".<clinit>#end";
                long endSiteId = SyncTransformer.registerSiteId(endSite);
                mv.visitLdcInsn(24); // BinarySchema.Event.CLASS_INIT_END
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitLdcInsn(endSiteId);
                mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        monitorClass,
                        monitorMethod,
                        "(ILjava/lang/Object;J)V",
                        false);
            }

            super.visitInsn(opcode);
        }
    }
}
