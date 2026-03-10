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
                    mv.visitInsn(Opcodes.DUP2); // [arrayRef, index, arrayRef, index]
                    mv.visitLdcInsn(eventType); // [arrayRef, index, arrayRef, index, type]
                    mv.visitInsn(Opcodes.DUP_X2); // [arrayRef, index, type, arrayRef, index, type]
                    mv.visitInsn(Opcodes.POP); // [arrayRef, index, type, arrayRef, index]
                    mv.visitLdcInsn(siteId); // [arrayRef, index, type, arrayRef, index, siteId]

                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod,
                            "(ILjava/lang/Object;II)V", false);
                } else {
                    // LASTORE / DASTORE
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
                    mv.visitInsn(Opcodes.DUP2); // [arrayRef, index, arrayRef, index]
                    mv.visitLdcInsn(eventType); // [arrayRef, index, arrayRef, index, type]
                    mv.visitInsn(Opcodes.DUP_X2); // [arrayRef, index, type, arrayRef, index, type]
                    mv.visitInsn(Opcodes.POP); // [arrayRef, index, type, arrayRef, index]
                    mv.visitLdcInsn(siteId); // [arrayRef, index, type, arrayRef, index, siteId]

                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod,
                            "(ILjava/lang/Object;II)V", false);
                } else {
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
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitInsn(Opcodes.DUP);
                    if (isReplay) {
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
                            emitAtomicCheckCall(valueType);
                        } else {
                            mv.visitVarInsn(Opcodes.ALOAD, receiverSlot);
                            if (isArrayAtomic && argTypes.length > 0) {
                                mv.visitVarInsn(Opcodes.ILOAD, argSlots[0]);
                            } else {
                                mv.visitLdcInsn(-1);
                            }
                            mv.visitLdcInsn(atomicEventType);
                            mv.visitLdcInsn(siteId);
                            emitAtomicCheckCall(returnTypeChar);
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
            // Everything else is read-modify-write
            return 22; // ATOMIC_RMW
        }

        // Parse return type from descriptor: "(III)Z" → 'Z', "()V" → 'V',
        // "()Ljava/lang/Object;" → 'L'
        private static char getReturnType(String descriptor) {
            return descriptor.charAt(descriptor.indexOf(')') + 1);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            String monitorMethod = isReplay ? "checkField" : "logField";
            boolean isWide = descriptor.startsWith("J") || descriptor.startsWith("D");
            int eventType = (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) ? 5 : 6;

            // Resolve volatility from the OWNER class, not just the current class.
            // This handles cross-class field accesses (e.g., Main accessing Counter.x).
            boolean isVolatile = SyncTransformer.isFieldVolatile(loader, owner, name);
            // Check if we need the IS_STATIC flag
            boolean isStatic = (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC);

            String siteString = className + "." + methodName + "#" + instructionId++;
            int siteId = SyncTransformer.registerSiteId(siteString);

            // Stack surgery to extract the owner object reference for logging
            if (opcode == Opcodes.PUTFIELD) {
                if (isWide) {
                    // Stack: [objectRef, wide_value(cat2)]
                    mv.visitInsn(Opcodes.DUP2_X1); // [wide_value, objectRef, wide_value] (DUP2_X1 Form 2: cat2 over
                                                   // cat1)
                    mv.visitInsn(Opcodes.POP2); // [wide_value, objectRef]
                    mv.visitInsn(Opcodes.DUP); // [wide_value, objectRef, objectRef_copy]
                } else {
                    // Stack: [objectRef, value(cat1)]
                    mv.visitInsn(Opcodes.DUP2); // [objectRef, value, objectRef, value]
                    mv.visitInsn(Opcodes.POP); // [objectRef, value, objectRef]
                }
            } else if (opcode == Opcodes.GETFIELD) {
                mv.visitInsn(Opcodes.DUP); // [objectRef, objectRef_copy]
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL); // GETSTATIC/PUTSTATIC — no owner instance
            }

            // Log the field access — objectRef (or null for static) is on top of stack
            mv.visitLdcInsn(eventType); // 5 or 6
            mv.visitInsn(Opcodes.SWAP);
            mv.visitLdcInsn(siteId);
            mv.visitInsn(isVolatile ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            mv.visitLdcInsn(isStatic ? 1 : 0);
            mv.visitLdcInsn(name);
            mv.visitLdcInsn(owner);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod,
                    "(ILjava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)V", false);

            // Restore stack order for wide PUTFIELD
            if (opcode == Opcodes.PUTFIELD && isWide) {
                // Stack is [wide_value, objectRef] — need [objectRef, wide_value]
                mv.visitInsn(Opcodes.DUP_X2); // [objectRef, wide_value, objectRef] (DUP_X2 Form 2: cat1 over cat2)
                mv.visitInsn(Opcodes.POP); // [objectRef, wide_value]
            }

            super.visitFieldInsn(opcode, owner, name, descriptor);
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
