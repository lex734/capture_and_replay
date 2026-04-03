package instr;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.InputStream;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.LocalVariablesSorter;

public class SyncTransformer implements ClassFileTransformer {

    // ---- Site ID registry (assigned at class-load/transform time, not runtime) ----
    // This ensures site IDs are identical across capture and replay runs,
    // regardless of thread scheduling during execution.
    private static final ConcurrentHashMap<String, Integer> siteRegistry = new ConcurrentHashMap<>();
    private static final AtomicInteger siteIdCounter = new AtomicInteger(1);

    public static int registerSiteId(String siteString) {
        return siteRegistry.computeIfAbsent(siteString, k -> siteIdCounter.getAndIncrement());
    }

    public static void resetSiteRegistry() {
        siteRegistry.clear();
        siteIdCounter.set(1);
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

        // set up ASM to read and write the class
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);

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
            String allocSite = className + "." + methodName + "#alloc_" + instructionId++;
            int allocSiteId = SyncTransformer.registerSiteId(allocSite);
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(allocSiteId);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "common/IdentityMapper", "registerAllocation",
                    "(Ljava/lang/Object;I)V", false);
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
        }

        @Override
        public void visitInsn(int opcode) {
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
                String siteString = className + "." + methodName + "#" + instructionId++;
                int siteId = SyncTransformer.registerSiteId(siteString);

                mv.visitInsn(Opcodes.DUP); // Keep the lock object
                mv.visitLdcInsn(eventType); // [lock, lock, type]
                mv.visitInsn(Opcodes.SWAP); // [lock, type, lock]
                mv.visitLdcInsn(siteId); // [lock, type, lock, siteId]

                // Matches CaptureMonitor.logSync(int type, Object lock, int siteId)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod,
                        "(ILjava/lang/Object;I)V", false);
                // Handle long/double array operations (category-2 values take 2 stack slots)
            } else if (opcode == Opcodes.LASTORE || opcode == Opcodes.DASTORE || opcode == Opcodes.LALOAD
                    || opcode == Opcodes.DALOAD) {
                String monitorMethod = isReplay ? "checkArray" : "logArray";
                int eventType = (opcode == Opcodes.LALOAD || opcode == Opcodes.DALOAD) ? 7 : 8;
                String siteString = className + "." + methodName + "#" + instructionId++;
                int siteId = SyncTransformer.registerSiteId(siteString);

                if (opcode == Opcodes.LALOAD || opcode == Opcodes.DALOAD) {
                    if (isReplay) {
                        // Stack: [arrayRef, index] — atomically read inside the coordination lock.
                        mv.visitLdcInsn(siteId);
                        if (opcode == Opcodes.DALOAD) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkArrayReadDouble",
                                "(Ljava/lang/Object;II)D", false);
                        } else {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkArrayReadLong",
                                "(Ljava/lang/Object;II)J", false);
                        }
                        return; // read value is on stack; skip super.visitInsn
                    }
                    mv.visitInsn(Opcodes.DUP2); // [arrayRef, index, arrayRef, index]
                    mv.visitLdcInsn(eventType); // [arrayRef, index, arrayRef, index, type]
                    mv.visitInsn(Opcodes.DUP_X2); // [arrayRef, index, type, arrayRef, index, type]
                    mv.visitInsn(Opcodes.POP); // [arrayRef, index, type, arrayRef, index]
                    mv.visitLdcInsn(siteId); // [arrayRef, index, type, arrayRef, index, siteId]

                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod,
                            "(ILjava/lang/Object;II)V", false);
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
                                "(DLjava/lang/Object;II)V", false);
                        } else {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkArrayWriteLong",
                                "(JLjava/lang/Object;II)V", false);
                        }
                        return; // write already done inside lock; skip super.visitInsn
                    }
                    mv.visitInsn(Opcodes.DUP2_X2);
                    mv.visitInsn(Opcodes.POP2);
                    mv.visitInsn(Opcodes.DUP2);
                    mv.visitLdcInsn(eventType);
                    mv.visitInsn(Opcodes.DUP_X2);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitLdcInsn(siteId);

                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod,
                            "(ILjava/lang/Object;II)V", false);
                    mv.visitInsn(Opcodes.DUP2_X2);
                    mv.visitInsn(Opcodes.POP2);
                }
            } else if (isArrayLoad(opcode) || isArrayStore(opcode)) {
                String monitorMethod = isReplay ? "checkArray" : "logArray";
                int eventType = isArrayLoad(opcode) ? 7 : 8;
                String siteString = className + "." + methodName + "#" + instructionId++;
                int siteId = SyncTransformer.registerSiteId(siteString);

                if (isArrayLoad(opcode)) {
                    if (isReplay) {
                        // Stack: [arrayRef, index] — atomically read inside the coordination lock.
                        mv.visitLdcInsn(siteId);
                        if (opcode == Opcodes.FALOAD) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkArrayReadFloat",
                                "(Ljava/lang/Object;II)F", false);
                        } else if (opcode == Opcodes.AALOAD) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkArrayReadObj",
                                "(Ljava/lang/Object;II)Ljava/lang/Object;", false);
                        } else { // IALOAD, BALOAD, CALOAD, SALOAD
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkArrayReadInt",
                                "(Ljava/lang/Object;II)I", false);
                        }
                        return; // read value is on stack; skip super.visitInsn
                    }
                    mv.visitInsn(Opcodes.DUP2); // [arrayRef, index, arrayRef, index]
                    mv.visitLdcInsn(eventType); // [arrayRef, index, arrayRef, index, type]
                    mv.visitInsn(Opcodes.DUP_X2); // [arrayRef, index, type, arrayRef, index, type]
                    mv.visitInsn(Opcodes.POP); // [arrayRef, index, type, arrayRef, index]
                    mv.visitLdcInsn(siteId); // [arrayRef, index, type, arrayRef, index, siteId]

                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod,
                            "(ILjava/lang/Object;II)V", false);
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
                            checkDesc = "(FLjava/lang/Object;II)V";
                        } else if (opcode == Opcodes.AASTORE) {
                            valType = Type.getObjectType("java/lang/Object");
                            checkMethod = "checkArrayWriteObj";
                            checkDesc = "(Ljava/lang/Object;Ljava/lang/Object;II)V";
                        } else { // IASTORE, BASTORE, CASTORE, SASTORE
                            valType = Type.INT_TYPE;
                            checkMethod = "checkArrayWriteInt";
                            checkDesc = "(ILjava/lang/Object;II)V";
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
                    mv.visitInsn(Opcodes.DUP_X2); // Stack: [value, arrayRef, index, value]
                    mv.visitInsn(Opcodes.POP); // Stack: [value, arrayRef, index]

                    mv.visitInsn(Opcodes.DUP2); // Stack: [value, arrayRef, index, arrayRef, index]
                    mv.visitLdcInsn(eventType); // Stack: [value, arrayRef, index, arrayRef, index, type]
                    mv.visitInsn(Opcodes.DUP_X2); // Stack: [value, arrayRef, index, type, arrayRef, index, type]
                    mv.visitInsn(Opcodes.POP); // Stack: [value, arrayRef, index, type, arrayRef, index]

                    mv.visitLdcInsn(siteId); // Stack: [value, arrayRef, index, type, arrayRef, index, siteId]
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod,
                            "(ILjava/lang/Object;II)V", false);
                    mv.visitInsn(Opcodes.DUP2_X1); // Stack: [arrayRef, index, value, arrayRef, index]
                    mv.visitInsn(Opcodes.POP2); // Stack: [arrayRef, index, value]
                }
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
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
                        mv.visitInsn(Opcodes.DUP);
                    } else {
                        // Stack surgery for join(long): [threadRef, longValue]
                        mv.visitInsn(Opcodes.DUP2_X1);
                        mv.visitInsn(Opcodes.POP2);
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
                                    "beginAtomicReplay", "(Ljava/lang/Object;III)V", false);

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

        /** Capture mode: ends the atomic bracket — seq assigned, trace written, lock released. */
        private void emitAtomicEndCaptureCall(Type valueType) {
            int sort = valueType.getSort();
            if (sort == Type.LONG) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "endAtomicCaptureLong",
                        "(JLjava/lang/Object;III)V", false);
            } else if (sort == Type.OBJECT || sort == Type.ARRAY) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "endAtomicCaptureObj",
                        "(Ljava/lang/Object;Ljava/lang/Object;III)V", false);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "endAtomicCaptureInt",
                        "(ILjava/lang/Object;III)V", false);
            }
        }

        private void emitAtomicEndCaptureCall(char returnTypeChar) {
            if (returnTypeChar == 'J' || returnTypeChar == 'D') {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "endAtomicCaptureLong",
                        "(JLjava/lang/Object;III)V", false);
            } else if (returnTypeChar == 'L' || returnTypeChar == '[') {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "endAtomicCaptureObj",
                        "(Ljava/lang/Object;Ljava/lang/Object;III)V", false);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "endAtomicCaptureInt",
                        "(ILjava/lang/Object;III)V", false);
            }
        }

        /** Replay mode, CAS path: injects captured result from trace. Stack before: [receiver, index, siteId]. */
        private void emitAtomicCasCheckCall(char returnTypeChar) {
            if (returnTypeChar == 'J' || returnTypeChar == 'D') {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkAtomicCasLong",
                        "(Ljava/lang/Object;II)J", false);
            } else if (returnTypeChar == 'L' || returnTypeChar == '[') {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkAtomicCasObj",
                        "(Ljava/lang/Object;II)Ljava/lang/Object;", false);
            } else {
                // Covers 'Z' (compareAndSet → boolean), 'I' (compareAndExchange → int), etc.
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkAtomicCasInt",
                        "(Ljava/lang/Object;II)I", false);
            }
        }

        /** Emits the appropriate checkFieldRead* call for the given field descriptor. */
        private void emitFieldReadCheckCall(String descriptor) {
            // Stack before call: [owner, siteId, isVolatile, isStatic, fieldName, ownerName]
            if (descriptor.equals("J")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldReadLong",
                    "(Ljava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)J", false);
            } else if (descriptor.equals("D")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldReadDouble",
                    "(Ljava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)D", false);
            } else if (descriptor.equals("F")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldReadFloat",
                    "(Ljava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)F", false);
            } else if (descriptor.startsWith("L") || descriptor.startsWith("[")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldReadObj",
                    "(Ljava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
                // CHECKCAST so the verifier sees the concrete type, not just Object.
                String castTarget = descriptor.startsWith("L")
                    ? descriptor.substring(1, descriptor.length() - 1)
                    : descriptor; // array descriptors are passed as-is
                mv.visitTypeInsn(Opcodes.CHECKCAST, castTarget);
            } else {
                // I, Z, B, S, C
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldReadInt",
                    "(Ljava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)I", false);
            }
        }

        /** Emits the appropriate checkFieldWrite* call for the given field descriptor. */
        private void emitFieldWriteCheckCall(String descriptor) {
            if (descriptor.equals("J")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldWriteLong",
                    "(JLjava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)V", false);
            } else if (descriptor.equals("D")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldWriteDouble",
                    "(DLjava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)V", false);
            } else if (descriptor.equals("F")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldWriteFloat",
                    "(FLjava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)V", false);
            } else if (descriptor.startsWith("L") || descriptor.startsWith("[")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldWriteObj",
                    "(Ljava/lang/Object;Ljava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)V", false);
            } else {
                // I, Z, B, S, C
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkFieldWriteInt",
                    "(ILjava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)V", false);
            }
        }

        /** Emits the appropriate logFieldRead* call for the given field descriptor (capture mode). */
        private void emitFieldReadLogCall(String descriptor) {
            if (descriptor.equals("J")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldReadLong",
                    "(Ljava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)J", false);
            } else if (descriptor.equals("D")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldReadDouble",
                    "(Ljava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)D", false);
            } else if (descriptor.equals("F")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldReadFloat",
                    "(Ljava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)F", false);
            } else if (descriptor.startsWith("L") || descriptor.startsWith("[")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldReadObj",
                    "(Ljava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
                String castTarget = descriptor.startsWith("L")
                    ? descriptor.substring(1, descriptor.length() - 1)
                    : descriptor;
                mv.visitTypeInsn(Opcodes.CHECKCAST, castTarget);
            } else {
                // I, Z, B, S, C
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldReadInt",
                    "(Ljava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)I", false);
            }
        }

        /** Emits the appropriate logFieldWrite* call for the given field descriptor (capture mode). */
        private void emitFieldWriteLogCall(String descriptor) {
            if (descriptor.equals("J")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldWriteLong",
                    "(JLjava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)V", false);
            } else if (descriptor.equals("D")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldWriteDouble",
                    "(DLjava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)V", false);
            } else if (descriptor.equals("F")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldWriteFloat",
                    "(FLjava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)V", false);
            } else if (descriptor.startsWith("L") || descriptor.startsWith("[")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldWriteObj",
                    "(Ljava/lang/Object;Ljava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)V", false);
            } else {
                // I, Z, B, S, C
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logFieldWriteInt",
                    "(ILjava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)V", false);
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
            // Resolve volatility from the OWNER class, not just the current class.
            // This handles cross-class field accesses (e.g., Main accessing Counter.x).
            boolean isVolatile = SyncTransformer.isFieldVolatile(loader, owner, name);
            // Check if we need the IS_STATIC flag
            boolean isStatic = (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC);

            String siteString = className + "." + methodName + "#" + instructionId++;
            int siteId = SyncTransformer.registerSiteId(siteString);

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
