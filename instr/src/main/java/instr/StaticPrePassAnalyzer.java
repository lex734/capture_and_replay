package instr;

import common.v1.AgentRuntimeConfig;
import common.v1.ClassKey;
import common.v1.ConcurrentEntryRoot;
import common.v1.ConcurrentRootKind;
import common.v1.MethodKey;
import common.v1.StaticPrePassResult;
import java.util.LinkedHashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class StaticPrePassAnalyzer {
    private StaticPrePassAnalyzer() {
    }

    static StaticPrePassResult analyzeClass(String className, byte[] classfileBuffer, AgentRuntimeConfig config) {
        if (className == null || classfileBuffer == null || config == null || !config.shouldInstrumentClass(className)) {
            return StaticPrePassResult.empty();
        }

        LinkedHashSet<ConcurrentEntryRoot> roots = new LinkedHashSet<>();
        LinkedHashSet<ClassKey> reachableClasses = new LinkedHashSet<>();
        LinkedHashSet<MethodKey> tierBMethods = new LinkedHashSet<>();
        LinkedHashSet<ClassKey> tierBClasses = new LinkedHashSet<>();
        ClassKey classKey = ClassKey.of(className);

        ClassReader reader = new ClassReader(classfileBuffer);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            private boolean classTouchesConcurrency;

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodKey methodKey = new MethodKey(classKey, name, descriptor);
                if (looksLikeConcurrentRoot(access, name, descriptor)) {
                    roots.add(new ConcurrentEntryRoot(kindFor(access, name, descriptor), methodKey));
                    reachableClasses.add(classKey);
                    classTouchesConcurrency = true;
                }
                if (config.shouldAttemptTierB(className) && isTierBEligibleMethod(access, name)) {
                    tierBMethods.add(methodKey);
                    tierBClasses.add(classKey);
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor,
                            boolean isInterface) {
                        if (isConcurrencyBridge(owner, methodName)) {
                            roots.add(new ConcurrentEntryRoot(ConcurrentRootKind.THREAD_START_CALLER, methodKey));
                            reachableClasses.add(classKey);
                            classTouchesConcurrency = true;
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (isArrayOpcode(opcode) && config.shouldAttemptTierB(className)) {
                            tierBMethods.add(methodKey);
                            tierBClasses.add(classKey);
                        }
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (classTouchesConcurrency) {
                    reachableClasses.add(classKey);
                }
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        return new StaticPrePassResult(roots, reachableClasses, tierBMethods, tierBClasses);
    }

    private static boolean looksLikeConcurrentRoot(int access, String name, String descriptor) {
        return "run".equals(name) && "()V".equals(descriptor)
                || "call".equals(name)
                || (name.startsWith("lambda$") && ((access & Opcodes.ACC_SYNTHETIC) != 0));
    }

    private static ConcurrentRootKind kindFor(int access, String name, String descriptor) {
        if ("run".equals(name) && "()V".equals(descriptor)) {
            return ConcurrentRootKind.RUNNABLE_RUN;
        }
        if ("call".equals(name)) {
            return ConcurrentRootKind.CALLABLE_CALL;
        }
        return ConcurrentRootKind.THREAD_ENTRY_LAMBDA;
    }

    private static boolean isTierBEligibleMethod(int access, String name) {
        return !"<init>".equals(name) && !"<clinit>".equals(name) && (access & Opcodes.ACC_ABSTRACT) == 0;
    }

    private static boolean isConcurrencyBridge(String owner, String name) {
        return "java/lang/Thread".equals(owner) && "start".equals(name)
                || "java/util/concurrent/Executor".equals(owner) && "execute".equals(name)
                || "java/util/concurrent/ExecutorService".equals(owner) && "submit".equals(name)
                || "java/util/concurrent/ForkJoinTask".equals(owner) && "fork".equals(name);
    }

    private static boolean isArrayOpcode(int opcode) {
        return opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD
                || opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE;
    }
}
