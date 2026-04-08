package instr;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.io.InputStream;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.LocalVariablesSorter;
import org.w3c.dom.events.EventTarget;

public class SyncTransformer implements ClassFileTransformer {

    private final String extraExclude; // optional extra package prefix to skip; may be null

    public SyncTransformer()                    { this.extraExclude = null; }
    public SyncTransformer(String extraExclude) { this.extraExclude = extraExclude; }

    // ---- Site ID registry (assigned at class-load/transform time, not runtime) ----
    // Site IDs are derived from the site string content, not registration order.
    // This makes them identical across capture and replay regardless of class-load order.
    private static final ConcurrentHashMap<String, Integer> siteRegistry = new ConcurrentHashMap<>();

    public static int registerSiteId(String siteString) {
        return siteRegistry.computeIfAbsent(siteString, k -> {
            // Derive a stable, content-based ID. Mix bits to reduce clustering from
            // the polynomial hashCode, then strip the sign bit. Reserve 0 for GLOBAL.
            int h = k.hashCode();
            h ^= (h >>> 16);
            h &= 0x7FFFFFFF;
            return (h == 0) ? 1 : h;
        });
    }

    public static void resetSiteRegistry() {
        siteRegistry.clear();
    }

    // ---- Global volatile field resolution ----
    // Maps "owner/className" → set of volatile field names.
    // Populated as classes are transformed; on cache miss for cross-class
    // field accesses, reads the owner class's bytecode via the classloader.
    private static final ConcurrentHashMap<String, Set<String>> volatileFieldCache = new ConcurrentHashMap<>();

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
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature,
                        Object value) {
                    if ((access & Opcodes.ACC_VOLATILE) != 0) {
                        volFields.add(name);
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);

            volatileFieldCache.put(owner, volFields);
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
                || className.startsWith("sun/")) {
            return null;
        }
        if (extraExclude != null && className.startsWith(extraExclude)) {
            return null;
        }

        // set up ASM to read and write the class
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            // Use the transformed class's own classloader when resolving types for
            // frame computation.  ASM's default ClassWriter uses the agent's loader,
            // which may not see classes in the target app's loader, causing it to
            // fall back to Object and produce frames that fail bytecode verification.
            final ClassLoader cl = loader;
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    ClassLoader toUse = cl != null ? cl : ClassLoader.getSystemClassLoader();
                    try {
                        Class<?> c1 = Class.forName(type1.replace('/', '.'), false, toUse);
                        Class<?> c2 = Class.forName(type2.replace('/', '.'), false, toUse);
                        if (c1.isAssignableFrom(c2)) return type1;
                        if (c2.isAssignableFrom(c1)) return type2;
                        if (c1.isInterface() || c2.isInterface()) return "java/lang/Object";
                        do { c1 = c1.getSuperclass(); } while (!c1.isAssignableFrom(c2));
                        return c1.getName().replace('.', '/');
                    } catch (Exception e) {
                        return "java/lang/Object";
                    }
                }
            };

            SyncClassVisitor visitor = new SyncClassVisitor(writer, className, loader);
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            return writer.toByteArray();
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    // begin by wrapping the class by wrapping methods within the class
    static class SyncClassVisitor extends ClassVisitor {
        private final String className;
        private final ClassLoader loader;
        private final String monitorClass;
        private final String monitorMethod;

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
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

            if (name.equals("<clinit>")) {
                MethodVisitor synced = new SyncMethodVisitor(access, descriptor, mv, name, name, loader);
                return new ClinitMethodVisitor(synced, className, monitorClass, monitorMethod);
            }

            return new SyncMethodVisitor(access, descriptor, mv, className, name, loader);
        }
    }

    static class SyncMethodVisitor extends LocalVariablesSorter {
        private final String className;
        private final String methodName;
        private final ClassLoader loader;
        private int instructionId = 0;

        // Tracks pending NEW instructions (type name → allocation site string) so we
        // can emit registerAllocation after the matching INVOKESPECIAL <init> call.
        // Outer = most recent NEW (stack order: top = innermost allocation).
        private final Deque<String> pendingNewTypes = new ArrayDeque<>();
        private final Deque<String> pendingNewSites = new ArrayDeque<>();

        private final boolean isReplay = "REPLAY".equals(System.getProperty("tool.mode"));
        private final String monitorClass = isReplay ? "replay/ReplayMonitor" : "capture/CaptureMonitor";
        private final String monitorDescriptor = "(ILjava/lang/Object;I)V";

        // Guard for the pre-super() window of constructors.
        // Before the super/this constructor call, `this` is `uninitializedThis` and
        // cannot be passed to any invokestatic as an Object.  Skip all instrumentation
        // in that window; it contains only field initialisation, not observable syncs.
        private final boolean isConstructor;
        private boolean superInitCalled;

        private boolean isArrayLoad(int opcode) {
            return opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD;
        }

        private boolean isArrayStore(int opcode) {
            return opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE;
        }

        // ---- Allocation tracking ----
        // NEW: push the type and allocation site; matched by visitMethodInsn.
        // NEWARRAY/ANEWARRAY/MULTIANEWARRAY: no <init>, so register inline.

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (!superInitCalled) { super.visitTypeInsn(opcode, type); return; }
            if (opcode == Opcodes.NEW) {
                String allocSite = className + "." + methodName + "#alloc_" + instructionId++;
                pendingNewTypes.push(type);
                pendingNewSites.push(allocSite);
                super.visitTypeInsn(opcode, type);
            } else if (opcode == Opcodes.ANEWARRAY) {
                String allocSite = className + "." + methodName + "#alloc_" + instructionId++;
                int allocSiteId = SyncTransformer.registerSiteId(allocSite);
                super.visitTypeInsn(opcode, type);
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(allocSiteId);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "common/IdentityMapper", "registerAllocation",
                        "(Ljava/lang/Object;I)V", false);
            } else {
                super.visitTypeInsn(opcode, type);
            }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            if (!superInitCalled) { super.visitIntInsn(opcode, operand); return; }
            if (opcode == Opcodes.NEWARRAY) {
                String allocSite = className + "." + methodName + "#alloc_" + instructionId++;
                int allocSiteId = SyncTransformer.registerSiteId(allocSite);
                super.visitIntInsn(opcode, operand);
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(allocSiteId);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "common/IdentityMapper", "registerAllocation",
                        "(Ljava/lang/Object;I)V", false);
            } else {
                super.visitIntInsn(opcode, operand);
            }
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            if (!superInitCalled) { super.visitMultiANewArrayInsn(descriptor, numDimensions); return; }
            String allocSite = className + "." + methodName + "#alloc_" + instructionId++;
            int allocSiteId = SyncTransformer.registerSiteId(allocSite);
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(allocSiteId);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "common/IdentityMapper", "registerAllocation",
                    "(Ljava/lang/Object;I)V", false);
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (!superInitCalled) { super.visitLdcInsn(value); return; }
            super.visitLdcInsn(value);
            if (value instanceof String) {
                // String literals are JVM-interned: one canonical object per unique value.
                // Use the string content as the site key so the same literal gets the
                // same BirthId.PoolString across capture and replay regardless of class-load order.
                // Heap strings constructed via `new String(...)` are a distinct bytecode
                // path (NEW + INVOKESPECIAL) and are handled by visitTypeInsn /
                // visitMethodInsn with a position-based BirthId.Heap, not here.
                String siteKey = "ldc:string:" + value;
                int siteId = SyncTransformer.registerSiteId(siteKey);
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(siteId);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "common/IdentityMapper", "registerPoolString",
                        "(Ljava/lang/String;I)V", false);
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... bsmArgs) {
            if (!superInitCalled) { super.visitInvokeDynamicInsn(name, descriptor, bsm, bsmArgs); return; }
            super.visitInvokeDynamicInsn(name, descriptor, bsm, bsmArgs);
            // Register lambda/method-reference instances created by LambdaMetafactory.
            // Stateless lambdas are JVM-cached singletons; registerAllocation is idempotent
            // so repeated calls for the same object are safe.
            // Capturing lambdas create a new instance per call; the site counter tracks
            // each instance just like regular NEW allocations.
            if (bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
                String allocSite = className + "." + methodName + "#lambda_" + instructionId++;
                int siteId = SyncTransformer.registerSiteId(allocSite);
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(siteId);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "common/IdentityMapper", "registerAllocation",
                        "(Ljava/lang/Object;I)V", false);
            }
        }

        private static boolean isAtomicArrayClass(String owner) {
            return owner.equals("java/util/concurrent/atomic/AtomicIntegerArray")
                    || owner.equals("java/util/concurrent/atomic/AtomicLongArray")
                    || owner.equals("java/util/concurrent/atomic/AtomicReferenceArray");
        }

        public SyncMethodVisitor(int access, String descriptor, MethodVisitor mv, String className, String methodName,
                ClassLoader loader) {
            super(Opcodes.ASM9, access, descriptor, mv);
            this.className = className;
            this.methodName = methodName;
            this.loader = loader;
            this.isConstructor = "<init>".equals(methodName);
            this.superInitCalled = !isConstructor; // non-constructors are always "ready"
        }
        
        @Override
        public void visitInsn(int opcode) {
            if (!superInitCalled) { super.visitInsn(opcode); return; }
            // Handle explicit/implicit throws — log before the throw so the
            // coordinator sees EXCEPTION_THROW in the right sequence position.
            if (opcode == Opcodes.ATHROW) {
                String logMethod = isReplay ? "checkException" : "logException";
                String siteString = className + "." + methodName + "#throw_" + instructionId++;
                int siteId = SyncTransformer.registerSiteId(siteString);
                // Stack: [..., exception]
                mv.visitInsn(Opcodes.DUP);       // [..., exception, exception]
                mv.visitLdcInsn(siteId);          // [..., exception, exception, siteId]
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, logMethod,
                        "(Ljava/lang/Object;I)V", false);
                // Stack restored to [..., exception]; ATHROW executes below via super.visitInsn
            }
            // Handle Intrinsic Locks
            if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                String monitorMethod = isReplay ? "checkSync" : "logSync";
                int eventType = (opcode == Opcodes.MONITORENTER) ? 1 : 2;
                int siteId = SyncTransformer.registerSiteId(className + "." + methodName + "#" + instructionId++);
                mv.visitInsn(Opcodes.DUP); 
                mv.visitLdcInsn(eventType); 
                mv.visitInsn(Opcodes.SWAP); 
                mv.visitLdcInsn(siteId); 
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod, "(ILjava/lang/Object;I)V", false);
                super.visitInsn(opcode);
                return;
            }

            // 2. Identify the type group
            boolean isLongOp = (opcode == Opcodes.LALOAD || opcode == Opcodes.DALOAD || opcode == Opcodes.LASTORE || opcode == Opcodes.DASTORE);
            boolean isObjOp  = (opcode == Opcodes.AALOAD || opcode == Opcodes.AASTORE);
            boolean isIntOp  = isArrayLoad(opcode) || (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE && opcode != Opcodes.AASTORE);

            if (isLongOp || isObjOp || isIntOp) {
                String typeSuffix = isLongOp ? "Long" : (isObjOp ? "Obj" : "Int");
                String valDesc    = isLongOp ? "J" : (isObjOp ? "Ljava/lang/Object;" : "I");
                boolean isLoad    = isLongOp ? (opcode == Opcodes.LALOAD || opcode == Opcodes.DALOAD) : isArrayLoad(opcode);
                
                int eventType = isLoad ? 7 : 8;
                int siteId = SyncTransformer.registerSiteId(className + "." + methodName + "#" + instructionId++);

                if (isReplay) {
                    /**
                     * REPLAY: execute the array access naturally to get the real element
                     * value, then call checkArrayT(naturalValue, ...) for divergence
                     * detection.  Writes do the same: pass the natural write-value, get
                     * back the approved value (natural or trace), and store that.
                     */
                    Type elemType = isLongOp ? Type.LONG_TYPE
                                  : isObjOp  ? Type.getType(Object.class)
                                  : Type.INT_TYPE;
                    int valStoreOp = isLongOp ? Opcodes.LSTORE : isObjOp ? Opcodes.ASTORE : Opcodes.ISTORE;
                    int valLoadOp  = isLongOp ? Opcodes.LLOAD  : isObjOp ? Opcodes.ALOAD  : Opcodes.ILOAD;
                    int idxLocal   = newLocal(Type.INT_TYPE);
                    int arrLocal   = newLocal(Type.getType(Object.class));
                    int valLocal   = newLocal(elemType);
                    // New checkArray descriptor: (naturalValue, eventType, array, index, siteId)
                    String checkDesc = "(" + valDesc + "ILjava/lang/Object;II)" + valDesc;

                    if (isLoad) {
                        // Stack: [array, index]
                        mv.visitVarInsn(Opcodes.ISTORE, idxLocal);
                        mv.visitVarInsn(Opcodes.ASTORE, arrLocal);
                        mv.visitVarInsn(Opcodes.ALOAD,  arrLocal);
                        mv.visitVarInsn(Opcodes.ILOAD,  idxLocal);
                        super.visitInsn(opcode); // actual XALOAD → [naturalValue]
                        mv.visitVarInsn(valStoreOp, valLocal);

                        mv.visitVarInsn(valLoadOp, valLocal);
                        mv.visitLdcInsn(eventType);
                        mv.visitVarInsn(Opcodes.ALOAD, arrLocal);
                        mv.visitVarInsn(Opcodes.ILOAD, idxLocal);
                        mv.visitLdcInsn(siteId);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkArray" + typeSuffix, checkDesc, false);
                        // Stack: [result]
                    } else {
                        // Stack: [array, index, value]
                        mv.visitVarInsn(valStoreOp, valLocal);
                        mv.visitVarInsn(Opcodes.ISTORE, idxLocal);
                        mv.visitVarInsn(Opcodes.ASTORE, arrLocal);

                        mv.visitVarInsn(valLoadOp, valLocal);
                        mv.visitLdcInsn(eventType);
                        mv.visitVarInsn(Opcodes.ALOAD, arrLocal);
                        mv.visitVarInsn(Opcodes.ILOAD, idxLocal);
                        mv.visitLdcInsn(siteId);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkArray" + typeSuffix, checkDesc, false);
                        // Stack: [approvedValue]
                        int resultLocal = newLocal(elemType);
                        mv.visitVarInsn(valStoreOp, resultLocal);
                        mv.visitVarInsn(Opcodes.ALOAD, arrLocal);
                        mv.visitVarInsn(Opcodes.ILOAD, idxLocal);
                        mv.visitVarInsn(valLoadOp, resultLocal);
                        super.visitInsn(opcode); // actual XASTORE
                    }
                    return;

                } else {
                    /** LOGGING **/
                    if (isLoad) {
                        // Use locals to save array/index, perform the load, log, then
                        // restore the loaded value onto the stack. Pure stack manipulation
                        // (DUP2 + DUP_X2) was consuming the value via the void log call,
                        // leaving the stack empty and causing ASM frame computation to crash.
                        int valStoreOp = isLongOp ? Opcodes.LSTORE : isObjOp ? Opcodes.ASTORE : Opcodes.ISTORE;
                        int valLoadOp  = isLongOp ? Opcodes.LLOAD  : isObjOp ? Opcodes.ALOAD  : Opcodes.ILOAD;
                        Type valType   = isLongOp ? Type.LONG_TYPE : isObjOp ? Type.getType(Object.class) : Type.INT_TYPE;
                        int idxLocal   = newLocal(Type.INT_TYPE);
                        int arrLocal   = newLocal(Type.getType(Object.class));
                        int valLocal   = newLocal(valType);
                        // Stack: [array, index]
                        mv.visitVarInsn(Opcodes.ISTORE, idxLocal);
                        mv.visitVarInsn(Opcodes.ASTORE, arrLocal);
                        mv.visitVarInsn(Opcodes.ALOAD,  arrLocal);
                        mv.visitVarInsn(Opcodes.ILOAD,  idxLocal);
                        super.visitInsn(opcode); // actual XALOAD → [value]
                        mv.visitVarInsn(valStoreOp, valLocal);
                        // Log: (value, eventType, array, index, siteId)
                        mv.visitVarInsn(valLoadOp, valLocal);
                        mv.visitLdcInsn(eventType);
                        mv.visitVarInsn(Opcodes.ALOAD, arrLocal);
                        mv.visitVarInsn(Opcodes.ILOAD, idxLocal);
                        mv.visitLdcInsn(siteId);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logArray" + typeSuffix, "(" + valDesc + "ILjava/lang/Object;II)V", false);
                        // Restore loaded value onto the stack
                        mv.visitVarInsn(valLoadOp, valLocal);
                        return;

                    } else {
                        /** LOG STORE: [array, index, VALUE] **/
                        // Snapshot value/index/array into locals, log, then restore for the actual store.
                        // Pure stack manipulation cannot work here because the log call consumes
                        // array and index, leaving nothing to restore from.
                        int valStoreOp, valLoadOp;
                        Type valType;
                        if (isLongOp) {
                            valStoreOp = Opcodes.LSTORE; valLoadOp = Opcodes.LLOAD;
                            valType = Type.LONG_TYPE;
                        } else if (isObjOp) {
                            valStoreOp = Opcodes.ASTORE; valLoadOp = Opcodes.ALOAD;
                            valType = Type.getType(Object.class);
                        } else {
                            valStoreOp = Opcodes.ISTORE; valLoadOp = Opcodes.ILOAD;
                            valType = Type.INT_TYPE;
                        }
                        int valLocal = newLocal(valType);
                        int idxLocal = newLocal(Type.INT_TYPE);
                        int arrLocal = newLocal(Type.getType(Object.class));
                        // Pop [VALUE, index, array] into locals (stack top = VALUE)
                        mv.visitVarInsn(valStoreOp, valLocal);
                        mv.visitVarInsn(Opcodes.ISTORE, idxLocal);
                        mv.visitVarInsn(Opcodes.ASTORE, arrLocal);
                        // Log call: (value, eventType, array, index, siteId)
                        mv.visitVarInsn(valLoadOp, valLocal);
                        mv.visitLdcInsn(eventType);
                        mv.visitVarInsn(Opcodes.ALOAD, arrLocal);
                        mv.visitVarInsn(Opcodes.ILOAD, idxLocal);
                        mv.visitLdcInsn(siteId);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logArray" + typeSuffix, "(" + valDesc + "ILjava/lang/Object;II)V", false);
                        // Restore [array, index, VALUE] for actual store
                        mv.visitVarInsn(Opcodes.ALOAD, arrLocal);
                        mv.visitVarInsn(Opcodes.ILOAD, idxLocal);
                        mv.visitVarInsn(valLoadOp, valLocal);
                        super.visitInsn(opcode);
                        return;
                    }
                }
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            // In a constructor, pass everything through until the super/this <init> call.
            // After that call `this` becomes fully initialized and instrumentation is safe.
            if (!superInitCalled) {
                if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)) {
                    superInitCalled = true;
                }
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
                int allocSiteId = SyncTransformer.registerSiteId(allocSite);
                // Execute the original constructor.
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                // After <init> returns (void), the initialized object is on top of the stack.
                // DUP it and register its allocation-time BirthId.
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(allocSiteId);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "common/IdentityMapper", "registerAllocation",
                        "(Ljava/lang/Object;I)V", false);
                return;
            }

            String siteString = className + "." + methodName + "#" + instructionId++;
            int siteId = SyncTransformer.registerSiteId(siteString);

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
            } else if (owner.equals("java/lang/Thread")) {
                if (name.equals("start")) {
                    // The receiver may be typed as Object by the verifier when loaded
                    // from an instrumented array access (checkArrayObj returns Object).
                    // CHECKCAST restores the Thread type for both preRegisterThread and
                    // invokevirtual; it is a no-op at runtime since the object is always
                    // a Thread here.
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Thread");
                    mv.visitInsn(Opcodes.DUP); // for logSyncCall
                    if (isReplay) {
                        mv.visitInsn(Opcodes.DUP); // extra copy consumed by preRegisterThread
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "preRegisterThread", "(Ljava/lang/Thread;)V", false);
                    }
                    logSyncCall(9, siteId); // THREAD_START
                } else if (name.equals("join")) {
                    // Determine if it is a timed join or infinite join
                    int eventType = descriptor.equals("()V") ? 10 : 18; // 10=JOIN, 18=JOIN_TIMEOUT

                    if (descriptor.equals("()V")) {
                        // Same as start(): receiver may be Object after array instrumentation.
                        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Thread");
                        mv.visitInsn(Opcodes.DUP);
                    } else {
                        // Stack surgery for join(long): [threadRef, longValue]
                        // Extract threadRef, cast it, then restore stack order.
                        mv.visitInsn(Opcodes.DUP2_X1);
                        mv.visitInsn(Opcodes.POP2);
                        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Thread"); // [long, Thread]
                        mv.visitInsn(Opcodes.DUP); // Duplicate threadRef
                        mv.visitInsn(Opcodes.DUP_X2); // Move duplicated ref behind long
                        mv.visitInsn(Opcodes.POP); // Clean up top
                    }
                    logSyncCall(eventType, siteId);
                } else if (name.equals("sleep")) {
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    logSyncCall(12, siteId); // THREAD_SLEEP
                } else if (name.equals("yield")) {
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    logSyncCall(14, siteId); // THREAD_YIELD
                } else if (name.equals("interrupt")) {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Thread");
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
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Thread");
                    mv.visitInsn(Opcodes.DUP);
                    logSyncCall(19, siteId); // THREAD_INTERRUPT_CHECK
                }
            } else if (owner.equals("java/lang/Object")) {
                if (name.equals("wait")) {
                    if (descriptor.equals("()V")) {
                        mv.visitInsn(Opcodes.DUP);
                    } else {
                        // Stack surgery for wait(long): [objRef, longValue]
                        mv.visitInsn(Opcodes.DUP2_X1);
                        mv.visitInsn(Opcodes.POP2);
                        mv.visitInsn(Opcodes.DUP);
                        mv.visitInsn(Opcodes.DUP_X2);
                        mv.visitInsn(Opcodes.POP);
                    }
                    logSyncCall(15, siteId); // THREAD_WAIT
                } else if (name.equals("notify") || name.equals("notifyAll")) {
                    mv.visitInsn(Opcodes.DUP);
                    logSyncCall(name.equals("notify") ? 16 : 17, siteId);
                }
            }

            // Detect atomic classes — save receiver + args BEFORE the call,
            // execute the call, then log with full context (WHERE + WHAT).
            if (owner.startsWith("java/util/concurrent/atomic/Atomic")) {
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
                        if (atomicEventType == 20 || atomicEventType == 21) {
                            // ATOMIC_READ / ATOMIC_WRITE: causal order guarantees correctness,
                            // execute naturally without interception
                            mv.visitVarInsn(Opcodes.ALOAD, receiverSlot);
                            for (int i = 0; i < argTypes.length; i++) {
                                mv.visitVarInsn(argTypes[i].getOpcode(Opcodes.ILOAD), argSlots[i]);
                            }
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        } else if (atomicEventType == 26) {
                            // ATOMIC_CAS: outcome is non-deterministic across schedules — inject
                            // captured result without executing; divergence is NOT a bug
                            mv.visitVarInsn(Opcodes.ALOAD, receiverSlot);
                            if (isArrayAtomic && argTypes.length > 0) {
                                mv.visitVarInsn(Opcodes.ILOAD, argSlots[0]);
                            } else {
                                mv.visitLdcInsn(-1);
                            }
                            mv.visitLdcInsn(atomicEventType);
                            mv.visitLdcInsn(siteId);
                            emitAtomicInjectCall(returnTypeChar);
                        } else {
                            // ATOMIC_RMW: execute naturally then check for divergence
                            mv.visitVarInsn(Opcodes.ALOAD, receiverSlot);
                            for (int i = 0; i < argTypes.length; i++) {
                                mv.visitVarInsn(argTypes[i].getOpcode(Opcodes.ILOAD), argSlots[i]);
                            }
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            if (returnTypeChar != 'V') {
                                Type retType = Type.getReturnType(descriptor);
                                int retLocal = newLocal(retType);
                                mv.visitVarInsn(retType.getOpcode(Opcodes.ISTORE), retLocal);
                                mv.visitVarInsn(retType.getOpcode(Opcodes.ILOAD), retLocal);
                                mv.visitVarInsn(Opcodes.ALOAD, receiverSlot);
                                if (isArrayAtomic && argTypes.length > 0) {
                                    mv.visitVarInsn(Opcodes.ILOAD, argSlots[0]);
                                } else {
                                    mv.visitLdcInsn(-1);
                                }
                                mv.visitLdcInsn(atomicEventType);
                                mv.visitLdcInsn(siteId);
                                emitAtomicRmwCheckCall(returnTypeChar);
                            }
                        }
                        return;
                    } else {

                        // 2. Restore the stack exactly as it was and execute the original call
                        mv.visitVarInsn(Opcodes.ALOAD, receiverSlot);
                        for (int i = 0; i < argTypes.length; i++) {
                            mv.visitVarInsn(argTypes[i].getOpcode(Opcodes.ILOAD), argSlots[i]);
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

                        // 3. Log the event — choose method based on return type / value type
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
                            emitAtomicLogCall(valueType);
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
                            emitAtomicLogCall(returnTypeChar);
                        }
                        return;
                    }
                }
            }

            // Execute the original method call
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (isBlockingCall(owner, name)) {
                String wakeupSite = className + "." + methodName + "#" + instructionId++;
                int wakeupSiteId = SyncTransformer.registerSiteId(wakeupSite);
                mv.visitInsn(Opcodes.ACONST_NULL);
                logSyncCall(13, wakeupSiteId); // THREAD_WAKEUP
            }
        }

        // Helper to keep the code clean and ensure the stack [eventType, object, site]
        // is correct
        private void logSyncCall(int type, int siteId) {
            String monitorMethod = isReplay ? "checkSync" : "logSync";
            mv.visitLdcInsn(type);
            mv.visitInsn(Opcodes.SWAP);
            mv.visitLdcInsn(siteId);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod, monitorDescriptor, false);
        }

        private void emitAtomicLogCall(Type valueType) {
            int sort = valueType.getSort();
            if (sort == Type.LONG) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logAtomicLong",
                        "(JLjava/lang/Object;III)V", false);
            } else if (sort == Type.OBJECT || sort == Type.ARRAY) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logAtomicObj",
                        "(Ljava/lang/Object;Ljava/lang/Object;III)V", false);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logAtomicInt",
                        "(ILjava/lang/Object;III)V", false);
            }
        }

        private void emitAtomicLogCall(char returnTypeChar) {
            if (returnTypeChar == 'J' || returnTypeChar == 'D') {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logAtomicLong",
                        "(JLjava/lang/Object;III)V", false);
            } else if (returnTypeChar == 'L' || returnTypeChar == '[') {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logAtomicObj",
                        "(Ljava/lang/Object;Ljava/lang/Object;III)V", false);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logAtomicInt",
                        "(ILjava/lang/Object;III)V", false);
            }
        }

        private void emitAtomicCheckCall(Type valueType) {
            int sort = valueType.getSort();
            if (sort == Type.LONG) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkAtomicLong",
                        "(JLjava/lang/Object;III)V", false);
            } else if (sort == Type.OBJECT || sort == Type.ARRAY) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkAtomicObj",
                        "(Ljava/lang/Object;Ljava/lang/Object;III)V", false);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkAtomicInt",
                        "(ILjava/lang/Object;III)V", false);
            }
        }

        private void emitAtomicCheckCall(char returnTypeChar) {
            if (returnTypeChar == 'J' || returnTypeChar == 'D') {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkAtomicLong",
                        "(Ljava/lang/Object;III)J", false);
            } else if (returnTypeChar == 'L' || returnTypeChar == '[') {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkAtomicObj",
                        "(Ljava/lang/Object;III)Ljava/lang/Object;", false);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkAtomicInt",
                        "(Ljava/lang/Object;III)I", false);
            }
        }

        // CAS: inject captured result without divergence check
        private void emitAtomicInjectCall(char returnTypeChar) {
            if (returnTypeChar == 'J' || returnTypeChar == 'D') {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "injectAtomicCasLong",
                        "(Ljava/lang/Object;III)J", false);
            } else if (returnTypeChar == 'L' || returnTypeChar == '[') {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "injectAtomicCasObj",
                        "(Ljava/lang/Object;III)Ljava/lang/Object;", false);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "injectAtomicCasInt",
                        "(Ljava/lang/Object;III)I", false);
            }
        }

        // RMW: execute + divergence check; (naturalValue, receiver, index, eventType, siteId) → naturalValue
        private void emitAtomicRmwCheckCall(char returnTypeChar) {
            if (returnTypeChar == 'J' || returnTypeChar == 'D') {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkAtomicRmwLong",
                        "(JLjava/lang/Object;III)J", false);
            } else if (returnTypeChar == 'L' || returnTypeChar == '[') {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkAtomicRmwObj",
                        "(Ljava/lang/Object;Ljava/lang/Object;III)Ljava/lang/Object;", false);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkAtomicRmwInt",
                        "(ILjava/lang/Object;III)I", false);
            }
        }

        private boolean isBlockingCall(String owner, String name) {
            return (owner.equals("java/lang/Thread") && (name.equals("join") || name.equals("sleep"))) ||
                    (owner.equals("java/lang/Object") && name.equals("wait")) ||
                    (owner.equals("java/util/concurrent/locks/LockSupport") &&
                            (name.equals("park") || name.equals("parkNanos") || name.equals("parkUntil")));
        }

        // Classify atomic method name → event type, or -1 to skip non-atomic methods
        private static int classifyAtomicOp(String name) {
            // Skip non-atomic methods that are by default used in the atomic class
            if (name.equals("<init>") || name.equals("toString") || name.equals("hashCode") ||
                    name.equals("intValue") || name.equals("longValue") || name.equals("floatValue") ||
                    name.equals("doubleValue") || name.equals("length") || name.equals("newUpdater"))
                return -1;
            // Writes
            if (name.startsWith("set") || name.equals("lazySet"))
                return 21; // ATOMIC_WRITE
            // Reads (get without "And")
            if (name.equals("get") || name.equals("getPlain") || name.equals("getOpaque") ||
                    name.equals("getAcquire") || name.equals("getReference") || name.equals("getStamp") ||
                    name.equals("isMarked"))
                return 20; // ATOMIC_READ
            // CAS operations: outcome is non-deterministic by design (different schedules
            // may produce different success/failure) — inject captured value during replay
            if (name.startsWith("compareAndSet") || name.startsWith("compareAndExchange") ||
                    name.startsWith("weakCompareAndSet"))
                return 26; // ATOMIC_CAS
            // Unconditional RMW (getAndAdd, getAndIncrement, getAndSet, updateAndGet, etc.)
            return 22; // ATOMIC_RMW
        }

        // Parse return type from descriptor: "(III)Z" → 'Z', "()V" → 'V',
        // "()Ljava/lang/Object;" → 'L'
        private static char getReturnType(String descriptor) {
            return descriptor.charAt(descriptor.indexOf(')') + 1);
        }
        
        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (!superInitCalled) { super.visitFieldInsn(opcode, owner, name, descriptor); return; }
            char typeCode = descriptor.charAt(0);
            boolean isWide = (typeCode == 'J' || typeCode == 'D');
            // Map to your specific method suffixes
            String typeSuffix = isWide ? "Long" : (typeCode == 'L' || typeCode == '[') ? "Obj" : "Int";
            String retDesc = isWide ? "J" : (typeCode == 'L' || typeCode == '[') ? "Ljava/lang/Object;" : "I";

            int eventType = (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) ? 5 : 6;
            boolean isStatic = (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC);
            boolean isVolatile = SyncTransformer.isFieldVolatile(loader, owner, name);
            int siteId = SyncTransformer.registerSiteId(className + "." + methodName + "#" + instructionId++);

            if (isReplay) {
                /**
                 * REPLAY: execute the field access naturally to get the real current value,
                 * then call checkFieldT(naturalValue, ...) which compares it against the
                 * trace.  If they match, the natural value is returned unchanged (no
                 * injection).  If they diverge, the trace value is returned and injected.
                 *
                 * Writes: the computed value is NOT discarded; it is passed to checkField
                 * so divergence can be detected, and the approved value (natural or trace)
                 * is what gets written to the field.
                 */
                // Determine correct local-variable types for this field descriptor.
                Type fieldType = Type.getType(descriptor);
                int valStoreOp = fieldType.getOpcode(Opcodes.ISTORE);
                int valLoadOp  = fieldType.getOpcode(Opcodes.ILOAD);
                int valLocal   = newLocal(fieldType);

                // New checkField descriptor: (naturalValue, eventType, owner, siteId, vol, stat, name, ownerName)
                String checkDesc = "(" + retDesc + "ILjava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)" + retDesc;

                if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) {
                    // --- READ ---
                    int ownerLocal = -1;
                    if (!isStatic) {
                        // Stack: [owner]
                        ownerLocal = newLocal(Type.getType(Object.class));
                        mv.visitVarInsn(Opcodes.ASTORE, ownerLocal); // save owner
                        mv.visitVarInsn(Opcodes.ALOAD,  ownerLocal); // restore for GETFIELD
                    }
                    // Emit actual read → [naturalValue]
                    super.visitFieldInsn(opcode, owner, name, descriptor);
                    mv.visitVarInsn(valStoreOp, valLocal); // save natural value

                    // Build call: checkFieldXxx(naturalValue, eventType, owner, siteId, ...)
                    mv.visitVarInsn(valLoadOp, valLocal);
                    mv.visitLdcInsn(eventType);
                    if (!isStatic) {
                        mv.visitVarInsn(Opcodes.ALOAD, ownerLocal);
                    } else {
                        mv.visitInsn(Opcodes.ACONST_NULL);
                    }
                    mv.visitLdcInsn(siteId);
                    mv.visitInsn(isVolatile ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                    mv.visitInsn(isStatic   ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                    mv.visitLdcInsn(name);
                    mv.visitLdcInsn(owner);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkField" + typeSuffix,
                            checkDesc, false);
                    // Stack: [result] — natural value, or trace value if divergent.

                } else {
                    // --- WRITE (PUTFIELD / PUTSTATIC) ---
                    // Stack: [owner, value] for PUTFIELD, [value] for PUTSTATIC
                    mv.visitVarInsn(valStoreOp, valLocal); // save natural write-value

                    int ownerLocal = -1;
                    if (!isStatic) {
                        ownerLocal = newLocal(Type.getType(Object.class));
                        mv.visitVarInsn(Opcodes.ASTORE, ownerLocal); // save owner
                    }

                    // Call checkFieldXxx(naturalValue, eventType, owner, siteId, ...)
                    mv.visitVarInsn(valLoadOp, valLocal);
                    mv.visitLdcInsn(eventType);
                    if (!isStatic) {
                        mv.visitVarInsn(Opcodes.ALOAD, ownerLocal);
                    } else {
                        mv.visitInsn(Opcodes.ACONST_NULL);
                    }
                    mv.visitLdcInsn(siteId);
                    mv.visitInsn(isVolatile ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                    mv.visitInsn(isStatic   ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                    mv.visitLdcInsn(name);
                    mv.visitLdcInsn(owner);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkField" + typeSuffix,
                            checkDesc, false);
                    // Stack: [approvedValue] — write this to the field.

                    if (!isStatic) {
                        // Need [owner, approvedValue] for PUTFIELD.
                        int resultLocal = newLocal(fieldType);
                        mv.visitVarInsn(valStoreOp, resultLocal);
                        mv.visitVarInsn(Opcodes.ALOAD, ownerLocal);
                        mv.visitVarInsn(valLoadOp, resultLocal);
                    }
                    // checkFieldObj returns java/lang/Object; cast to concrete type before write.
                    if (typeSuffix.equals("Obj") && !descriptor.equals("Ljava/lang/Object;")) {
                        String castType = descriptor.startsWith("[")
                                ? descriptor
                                : descriptor.substring(1, descriptor.length() - 1);
                        mv.visitTypeInsn(Opcodes.CHECKCAST, castType);
                    }
                    super.visitFieldInsn(opcode, owner, name, descriptor);
                    return;
                }

                // For reads: checkFieldObj returns java/lang/Object; cast to the concrete type.
                if (typeSuffix.equals("Obj") && !descriptor.equals("Ljava/lang/Object;")) {
                    String castType = descriptor.startsWith("[")
                            ? descriptor
                            : descriptor.substring(1, descriptor.length() - 1);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, castType);
                }
                return;

            } else {
                /** LOGGING: logFieldT(value, ...) called BEFORE stores, AFTER loads **/
                if (opcode == Opcodes.PUTFIELD) {
                    if (isWide) {
                        // Stack: [owner(1), val(2)]
                        mv.visitInsn(Opcodes.DUP2_X1); // [val(2), owner(1), val(2)]
                        mv.visitInsn(Opcodes.DUP2_X1); // [val(2), val(2), owner(1), val(2)]
                        mv.visitInsn(Opcodes.POP2);    // [val(2), val(2), owner(1)]
                    } else {
                        // Stack: [owner(1), val(1)]
                        mv.visitInsn(Opcodes.DUP2);    // [owner, val, owner, val]
                        mv.visitInsn(Opcodes.SWAP);    // [owner, val, val, owner] — bottom pair for PUTFIELD, top pair [val,owner] for log
                    }
                    // Prep Log Args
                    pushLogArgs(typeSuffix, retDesc, eventType, siteId, isVolatile, isStatic, name, owner);
                    super.visitFieldInsn(opcode, owner, name, descriptor);

                } else if (opcode == Opcodes.PUTSTATIC) {
                    if (isWide) mv.visitInsn(Opcodes.DUP2); else mv.visitInsn(Opcodes.DUP);
                    mv.visitInsn(Opcodes.ACONST_NULL); // owner
                    pushLogArgs(typeSuffix, retDesc, eventType, siteId, isVolatile, isStatic, name, owner);
                    super.visitFieldInsn(opcode, owner, name, descriptor);

                } else if (opcode == Opcodes.GETFIELD) {
                    mv.visitInsn(Opcodes.DUP); // Save owner: [owner, owner]
                    super.visitFieldInsn(opcode, owner, name, descriptor); // [owner, value]

                    // Duplicate value so one copy is passed to logField and one remains
                    // for the program to consume (same pattern as atomics DUP-before-log).
                    if (isWide) {
                        // DUP2_X1: [val_copy(wide), owner, val] — val_copy preserved at bottom
                        mv.visitInsn(Opcodes.DUP2_X1);
                    } else {
                        // DUP_X1 + SWAP: [owner, value] → [val_copy, owner, value] → [val_copy, value, owner]
                        mv.visitInsn(Opcodes.DUP_X1);
                        mv.visitInsn(Opcodes.SWAP);
                    }
                    pushLogArgs(typeSuffix, retDesc, eventType, siteId, isVolatile, isStatic, name, owner);

                } else { // GETSTATIC
                    super.visitFieldInsn(opcode, owner, name, descriptor); // [value]
                    // DUP before handing off to pushLogArgs so the program copy stays at
                    // the bottom of the stack after the void log call returns.
                    if (isWide) mv.visitInsn(Opcodes.DUP2); else mv.visitInsn(Opcodes.DUP);
                    mv.visitInsn(Opcodes.ACONST_NULL); // owner (null for static)
                    pushLogArgs(typeSuffix, retDesc, eventType, siteId, isVolatile, isStatic, name, owner);
                }
                return;
            }
        }

        private void pushLogArgs(String suffix, String valDesc, int type, int siteId, boolean vol, boolean stat, String name, String own) {
            // Stack is [value(1 or 2), owner(1)]
            mv.visitLdcInsn(type);
            // Stack: [value, owner, type]
            if (valDesc.equals("J") || valDesc.equals("D")) {
                // Value is 2 slots. We need type to go BEFORE owner but AFTER value.
                // Current: [V_hi, V_lo, Owner, Type]
                mv.visitInsn(Opcodes.SWAP); // [V_hi, V_lo, Type, Owner]
                // Now move Type behind V: [Type, V_hi, V_lo, Owner] -> Hard with SWAP.
                // Let's assume your logField signature is (Value, Type, Owner, ...)
                // If Type is already after value, we just need siteId etc.
            } else {
                mv.visitInsn(Opcodes.SWAP); // [value, type, owner]
            }
            
            mv.visitLdcInsn(siteId);
            mv.visitInsn(vol ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            mv.visitInsn(stat ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            mv.visitLdcInsn(name);
            mv.visitLdcInsn(own);

            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logField" + suffix,
                    "(" + valDesc + "ILjava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)V", false);
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
            int beginSiteId = SyncTransformer.registerSiteId(beginSite);
            mv.visitLdcInsn(23); // BinarySchema.Event.CLASS_INIT_BEGIN
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitLdcInsn(beginSiteId);
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    monitorClass,
                    monitorMethod,
                    "(ILjava/lang/Object;I)V",
                    false);
        }

        @Override
        public void visitInsn(int opcode) {
            // For normal returns and throws, log CLASS_INIT_END before exiting
            if (opcode == Opcodes.RETURN) {
                String endSite = className + ".<clinit>#end";
                int endSiteId = SyncTransformer.registerSiteId(endSite);
                mv.visitLdcInsn(24); // BinarySchema.Event.CLASS_INIT_END
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitLdcInsn(endSiteId);
                mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        monitorClass,
                        monitorMethod,
                        "(ILjava/lang/Object;I)V",
                        false);
            }

            super.visitInsn(opcode);
        }
    }
}
