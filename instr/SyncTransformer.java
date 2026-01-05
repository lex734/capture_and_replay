package instr;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

public class SyncTransformer implements ClassFileTransformer {

 @Override
 public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
 
  // prevent recursion
  if (className == null || className.startsWith("instr/") || 
            className.startsWith("common/") || className.startsWith("capture/") || 
            className.startsWith("replay/")) {
            return null;
        }

  // set up ASM to read and write the class
  try {
    ClassReader reader = new ClassReader(classfileBuffer);
    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);

    SyncClassVisitor visitor = new SyncClassVisitor(writer);
    reader.accept(visitor, 0);

    return writer.toByteArray();
  } catch (Throwable t) {
    t.printStackTrace();
    return null;
  }
 }

  // begin by wrapping the class by wrapping methods within the class
 static class SyncClassVisitor extends ClassVisitor {
  public SyncClassVisitor(ClassVisitor cv) {
    super(Opcodes.ASM9, cv);
  }

  @Override
  public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
    return new SyncMethodVisitor(mv);
  }
 }

 static class SyncMethodVisitor extends MethodVisitor {

    private final boolean isReplay = "REPLAY".equals(System.getProperty("tool.mode"));
    private final String monitorClass = isReplay ? "replay/ReplayMonitor" : "capture/CaptureMonitor";
    private final String monitorMethod = isReplay ? "checkSync" : "logSync";

    public SyncMethodVisitor(MethodVisitor mv) {
      super(Opcodes.ASM9, mv);
    }
    
    @Override
    public void visitInsn(int opcode) {
      if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
        int eventType = (opcode == Opcodes.MONITORENTER) ? 1 : 2;
                    
        // 1. Stack has [lockObj]
        mv.visitInsn(Opcodes.DUP);             // Stack: [lockObj, lockObj]
        mv.visitLdcInsn(eventType);            // Stack: [lockObj, lockObj, eventType]
                    
        // 2. Call consumes the top two elements
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod, "(Ljava/lang/Object;I)V", false);
                    
        // 3. Stack is back to [lockObj], ready for the original opcode
      }
    super.visitInsn(opcode);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
      if (owner.equals("java/util/concurrent/locks/LockSupport")) {
        if (name.equals("park") && descriptor.equals("(Ljava/lang/Object;)V")) {
          // Stack has [lockObj]
          mv.visitInsn(Opcodes.DUP); 
          mv.visitLdcInsn(3); // THREAD_PARK
          mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod, "(Ljava/lang/Object;I)V", false);
        } else if (name.equals("unpark") && descriptor.equals("(Ljava/lang/Thread;)V")) {
          // Stack has [threadObj]
          mv.visitInsn(Opcodes.DUP);
          mv.visitLdcInsn(4); // THREAD_UNPARK
          mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, monitorMethod, "(Ljava/lang/Object;I)V", false);
        }
      }
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }
  }
}
