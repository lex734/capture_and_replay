package instr;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

public class SyncTransformer implements ClassFileTransformer {

 @Override
 public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
 
  // prevent recursion
  if (className.startsWith("instr/") || className.startsWith("core/")) {
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
  public SyncMethodVisitor(MethodVisitor mv) {
    super(Opcodes.ASM9, mv);
  }

  @Override
  public void visitInsn(int opcode) {
    if (opcode == Opcodes.MONITORENTER) {
      mv.visitInsn(Opcodes.DUP); // make a copy of object to lock on stack
      mv.visitLdcInsn(1); // push integer on stack
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, "core/CaptureMonitor", "logSync", "(Ljava/lang/Object;I)V", false); // call core.CaptureMonitor.logSync(Object lock, int event)
      // stack is now back to original state
    }
    else if (opcode == Opcodes.MONITOREXIT) {
      mv.visitInsn(Opcodes.DUP);
      mv.visitLdcInsn(2);
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, "core/CaptureMonitor", "logSync", "(Ljava/lang/Object;I)V", false);
    }
    super.visitInsn(opcode); // execute the original instruction
  }

  @Override
  public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
    if (owner.equals("java/util/concurrent/locks/LockSupport")) {
      if (name.equals("park") && descriptor.equals("(Ljava/lang/Object;)V")) {
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "core/CaptureMonitor", "logSync", "(Ljava/lang/Object;I)V", false);
      } else if (name.equals("unpark") && descriptor.equals("(Ljava/lang/Thread;)V")) {
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(4);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "core/CaptureMonitor", "logSync", "(Ljava/lang/Object;I)V", false);
      }
    }
    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
  }
 }
}
