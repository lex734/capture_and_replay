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
import org.w3c.dom.events.EventTarget;

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
                    if (isLoad) {
                        // Stack: [array, index]
                        // Push eventType then slide it under [array, index] so the call
                        // sees (eventType, array, index, siteId) and consumes all four,
                        // leaving just [returned_value] on the stack.
                        mv.visitLdcInsn(eventType);
                        mv.visitInsn(Opcodes.DUP_X2);   // [eventType, array, index, eventType]
                        mv.visitInsn(Opcodes.POP);       // [eventType, array, index]
                        mv.visitLdcInsn(siteId);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkArray" + typeSuffix, "(ILjava/lang/Object;II)" + valDesc, false);
                    } else {
                        // Stack: [array, index, value]
                        // Pop value; DUP2 keeps [array, index] for the actual store instruction.
                        if (isLongOp) mv.visitInsn(Opcodes.POP2); else mv.visitInsn(Opcodes.POP);
                        mv.visitInsn(Opcodes.DUP2);
                        mv.visitLdcInsn(eventType);
                        mv.visitInsn(Opcodes.DUP_X2);
                        mv.visitInsn(Opcodes.POP);
                        mv.visitLdcInsn(siteId);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkArray" + typeSuffix, "(ILjava/lang/Object;II)" + valDesc, false);
                        super.visitInsn(opcode);
                    }
                    return;

                } else {
                    /** LOGGING **/
                    if (isLoad) {
                        mv.visitInsn(Opcodes.DUP2); 
                        super.visitInsn(opcode); // [array, index, VALUE]
                        
                        // Move VALUE to bottom: [VALUE, array, index]
                        if (isLongOp) {
                            mv.visitInsn(Opcodes.DUP2_X2); mv.visitInsn(Opcodes.POP2);
                        } else {
                            mv.visitInsn(Opcodes.DUP_X2); mv.visitInsn(Opcodes.POP);
                        }
                        
                        mv.visitLdcInsn(eventType); 
                        mv.visitInsn(Opcodes.DUP_X2); 
                        mv.visitInsn(Opcodes.POP); 
                        mv.visitLdcInsn(siteId); 
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logArray" + typeSuffix, "(" + valDesc + "ILjava/lang/Object;II)V", false);
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
                /** REPLAY: checkFieldT(...) returns the value to be used **/
                if (!isStatic) {
                    if (opcode == Opcodes.PUTFIELD) {
                        if (isWide) mv.visitInsn(Opcodes.POP2); else mv.visitInsn(Opcodes.POP);
                        // DUP owner so checkField can take one copy as its param
                        // and the original copy remains for the actual PUTFIELD below.
                        mv.visitInsn(Opcodes.DUP);
                    }
                    // Stack: [owner] for GETFIELD, [owner, owner] for PUTFIELD
                } else {
                    if (opcode == Opcodes.PUTSTATIC) {
                        if (isWide) mv.visitInsn(Opcodes.POP2); else mv.visitInsn(Opcodes.POP);
                    }
                    mv.visitInsn(Opcodes.ACONST_NULL); // owner is null for static
                }

                // Args: (int type, Object owner, int siteId, boolean vol, boolean stat, String name, String ownerName)
                mv.visitLdcInsn(eventType);
                mv.visitInsn(Opcodes.SWAP); // [type, owner]
                mv.visitLdcInsn(siteId);
                mv.visitInsn(isVolatile ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                mv.visitInsn(isStatic ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                mv.visitLdcInsn(name);
                mv.visitLdcInsn(owner);

                mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "checkField" + typeSuffix,
                        "(ILjava/lang/Object;IZZLjava/lang/String;Ljava/lang/String;)" + retDesc, false);

                // checkFieldObj returns java/lang/Object; the JVM verifier requires the
                // actual field type on the stack before any invokevirtual / putfield use.
                if (typeSuffix.equals("Obj") && !descriptor.equals("Ljava/lang/Object;")) {
                    String castType = descriptor.startsWith("[")
                            ? descriptor
                            : descriptor.substring(1, descriptor.length() - 1);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, castType);
                }

                if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) {
                    super.visitFieldInsn(opcode, owner, name, descriptor);
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
                        mv.visitInsn(Opcodes.DUP);     // [owner, val, owner, val, val]
                        mv.visitInsn(Opcodes.DUP_X2);  // [owner, val, val, owner, val, val]
                        mv.visitInsn(Opcodes.POP2);    // [owner, val, val, owner]
                        mv.visitInsn(Opcodes.DUP_X2);  // [owner, val, owner, val, val, owner]
                        mv.visitInsn(Opcodes.POP);     // [owner, val, val, owner] (This is the pair for log)
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
