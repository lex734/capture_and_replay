package instr;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.HashMap;
import org.objectweb.asm.*;

public class SyncTransformer implements ClassFileTransformer {

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    // prevent recursion
    if (className == null || className.startsWith("instr/") || 
              className.startsWith("common/") || className.startsWith("capture/") || 
              className.startsWith("replay/") || className.startsWith("java/") || className.startsWith("jdk/") || className.startsWith("sun/")) {
              return null;
          }

    // set up ASM to read and write the class
    try {
      ClassReader reader = new ClassReader(classfileBuffer);
      ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);

      SyncClassVisitor visitor = new SyncClassVisitor(writer, className);
      reader.accept(visitor, 0);

      return writer.toByteArray();
    } catch (Throwable t) {
      t.printStackTrace();
      return null;
    }
  }

  // begin by wrapping the class by wrapping methods within the class
  static class SyncClassVisitor extends ClassVisitor {
    private final String className;
    private final Map<String, Boolean> volatileFields = new HashMap<>();

    public SyncClassVisitor(ClassVisitor cv, String className) {
      super(Opcodes.ASM9, cv);
      this.className = className;
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
      if ((access & Opcodes.ACC_VOLATILE) != 0) {
        volatileFields.put(name, true);
      }
      return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
      return new SyncMethodVisitor(mv, className, name, volatileFields);
    }
  }

  static class SyncMethodVisitor extends MethodVisitor {
    private final String className;
    private final String methodName;
    private final java.util.Map<String, Boolean> volatileFields;
    private int instructionId = 0;

    // private final boolean isReplay = "REPLAY".equals(System.getProperty("tool.mode"));
    // private final String monitorClass = isReplay ? "replay/ReplayMonitor" : "capture/CaptureMonitor";
    // private final String monitorMethod = isReplay ? "checkSync" : "logSync";
    private final String monitorClass = "capture/CaptureMonitor";
    private final String monitorDescriptor = "(ILjava/lang/Object;Ljava/lang/String;)V";
    
    private boolean isArrayLoad(int opcode) {
      return opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD;
    }

    private boolean isArrayStore(int opcode) {
      return opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE;
    }

    public SyncMethodVisitor(MethodVisitor mv, String className, String methodName, Map<String, Boolean> volatileFields) {
      super(Opcodes.ASM9, mv);
      this.className = className;
      this.methodName = methodName;
      this.volatileFields = volatileFields;
    }
    
    @Override
    public void visitInsn(int opcode) {
        // Handle Intrinsic Locks
        if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
            int eventType = (opcode == Opcodes.MONITORENTER) ? 1 : 2;
            String siteString = className + "." + methodName + "#" + instructionId++;

            mv.visitInsn(Opcodes.DUP);         // Keep the lock object
            mv.visitLdcInsn(eventType);        // [lock, lock, type]
            mv.visitInsn(Opcodes.SWAP);        // [lock, type, lock]
            mv.visitLdcInsn(siteString);           // [lock, type, lock, siteString]
            
            // Matches CaptureMonitor.logSync(int type, Object lock, String siteString)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logSync", "(ILjava/lang/Object;Ljava/lang/String;)V", false);
        } else if (opcode == Opcodes.LASTORE || opcode == Opcodes.DASTORE || opcode == Opcodes.LALOAD || opcode == Opcodes.DALOAD) {
        } else if (isArrayLoad(opcode) || isArrayStore(opcode)) {
          int eventType = isArrayLoad(opcode) ? 7 : 9;
          String siteString = className + "." + methodName + "#" + instructionId++;

          if (isArrayLoad(opcode)) {
            // Stack: [arrayRef, index]
            mv.visitInsn(Opcodes.DUP2);        // [arrayRef, index, arrayRef, index]
            mv.visitLdcInsn(eventType);        // [arrayRef, index, arrayRef, index, type]
            mv.visitInsn(Opcodes.DUP_X2);      // [arrayRef, index, type, arrayRef, index, type]
            mv.visitInsn(Opcodes.POP);         // [arrayRef, index, type, arrayRef, index]
            mv.visitLdcInsn(siteString);           // [arrayRef, index, type, arrayRef, index, siteString]

            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logArray", "(ILjava/lang/Object;ILjava/lang/String;)V", false);
          } else {
            mv.visitInsn(Opcodes.DUP_X2);      // Stack: [value, arrayRef, index, value]
            mv.visitInsn(Opcodes.POP);          // Stack: [value, arrayRef, index]
            
            mv.visitInsn(Opcodes.DUP2);         // Stack: [value, arrayRef, index, arrayRef, index]
            mv.visitLdcInsn(eventType);         // Stack: [value, arrayRef, index, arrayRef, index, type]
            mv.visitInsn(Opcodes.DUP_X2);       // Stack: [value, arrayRef, index, type, arrayRef, index, type]
            mv.visitInsn(Opcodes.POP);          // Stack: [value, arrayRef, index, type, arrayRef, index]
            
            mv.visitLdcInsn(siteString);            // Stack: [value, arrayRef, index, type, arrayRef, index, siteId]
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logArray", "(ILjava/lang/Object;ILjava/lang/String;)V", false);
            mv.visitInsn(Opcodes.DUP2_X1);      // Stack: [arrayRef, index, value, arrayRef, index]
            mv.visitInsn(Opcodes.POP2);         // Stack: [arrayRef, index, value]
          }
        }
        super.visitInsn(opcode);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        String siteString = className + "." + methodName + "#" + instructionId++;

        if (owner.equals("java/util/concurrent/locks/LockSupport")) {
            if (name.equals("park")) {
                if (descriptor.equals("(Ljava/lang/Object;)V")) {
                    mv.visitInsn(Opcodes.DUP); 
                } else {
                    mv.visitInsn(Opcodes.ACONST_NULL);
                }
                logSyncCall(3, siteString); // THREAD_PARK
            } 
            else if (name.equals("unpark")) {
                mv.visitInsn(Opcodes.DUP);
                logSyncCall(4, siteString); // THREAD_UNPARK
            }
        } 
        else if (owner.equals("java/lang/Thread")) {
            if (name.equals("start")) {
                mv.visitInsn(Opcodes.DUP);
                logSyncCall(9, siteString); // THREAD_START
            }
            else if (name.equals("join")) {
                // Determine if it is a timed join or infinite join
                int eventType = descriptor.equals("()V") ? 10 : 18; // 10=JOIN, 18=JOIN_TIMEOUT
                
                if (descriptor.equals("()V")) {
                    mv.visitInsn(Opcodes.DUP);
                } else {
                    // Stack surgery for join(long): [threadRef, longValue]
                    mv.visitInsn(Opcodes.DUP2_X1); 
                    mv.visitInsn(Opcodes.POP2); 
                    mv.visitInsn(Opcodes.DUP);      // Duplicate threadRef
                    mv.visitInsn(Opcodes.DUP_X2);   // Move duplicated ref behind long
                    mv.visitInsn(Opcodes.POP);      // Clean up top
                }
                logSyncCall(eventType, siteString);
            }
            else if (name.equals("sleep")) {
                mv.visitInsn(Opcodes.ACONST_NULL); 
                logSyncCall(12, siteString); // THREAD_SLEEP
            }
            else if (name.equals("yield")) {
                mv.visitInsn(Opcodes.ACONST_NULL);
                logSyncCall(14, siteString); // THREAD_YIELD
            }
        }
        else if (owner.equals("java/lang/Object")) {
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
                logSyncCall(15, siteString); // THREAD_WAIT
            } 
            else if (name.equals("notify") || name.equals("notifyAll")) {
                mv.visitInsn(Opcodes.DUP); 
                logSyncCall(name.equals("notify") ? 16 : 17, siteString);
            }
        }

        // Execute the original method call
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        if (isBlockingCall(owner, name)) {
            mv.visitLdcInsn(siteString + "_wakeup");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logWakeup", "(Ljava/lang/String;)V", false);
        }
    }

    // Helper to keep the code clean and ensure the stack [eventType, object, site] is correct
    private void logSyncCall(int type, String site) {
        mv.visitLdcInsn(type);
        mv.visitInsn(Opcodes.SWAP);
        mv.visitLdcInsn(site);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logSync", monitorDescriptor, false);
    }

    private boolean isBlockingCall(String owner, String name) {
        return (owner.equals("java/lang/Thread") && (name.equals("join") || name.equals("sleep"))) ||
               (owner.equals("java/lang/Object") && name.equals("wait")) ||
               (owner.equals("java/util/concurrent/locks/LockSupport") && name.equals("park"));
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
      if (descriptor.startsWith("J") || descriptor.startsWith("D")) {
      } else {
        int eventType = (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) ? 5 : 6;
        
        boolean isVolatile = volatileFields.getOrDefault(name, false);
        // Check if we need the IS_STATIC flag
        boolean isStatic = (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC);

        String siteString = className + "." + methodName + "#" + instructionId++;

        // 3. Stack Surgery (Still needed to get the 'owner' for the logger)
        if (opcode == Opcodes.PUTFIELD) {
            mv.visitInsn(Opcodes.DUP2); 
            mv.visitInsn(Opcodes.POP);
        } else if (opcode == Opcodes.GETFIELD) {
            mv.visitInsn(Opcodes.DUP);
        } else {
            mv.visitInsn(Opcodes.ACONST_NULL); // GETSTATIC/PUTSTATIC has no owner
        }

        // 4. Call logField
        mv.visitLdcInsn(eventType);        // 5 or 6
        mv.visitInsn(Opcodes.SWAP); 
        mv.visitLdcInsn(siteString);
        mv.visitInsn(isVolatile ? Opcodes.ICONST_1 : Opcodes.ICONST_0);    // Volatile (set to 1 if you add volatile check)
        mv.visitLdcInsn(isStatic ? 1 : 0); // Static flag
        mv.visitLdcInsn(name);
        mv.visitLdcInsn(className);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, monitorClass, "logField", "(ILjava/lang/Object;Ljava/lang/String;ZZLjava/lang/String;Ljava/lang/String;)V", false);
      }
      super.visitFieldInsn(opcode, owner, name, descriptor);
    }
  }
}
