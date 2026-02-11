package instr;

import common.BinarySchema;
import common.IdentityMapper;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;
import java.io.File;

public class Agent {
  public static void premain(String agentArgs, Instrumentation inst) {
    try {
      System.out.println("[Agent] Initializing Recorder...");
      IdentityMapper.reset();
      java.net.URI agentUri = Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI();
      File agentJar = new File(agentUri);
      
      // 2. Get the 'libs' folder (the parent of the agent jar)
      File libsDir = agentJar.getParentFile(); 

      // 3. Locate the common JAR in that same 'libs' folder
      File coreJar = new File(libsDir, "trace-common.jar");

      // DEBUG: Let's see exactly what we found
      System.out.println("[Agent] Found Agent at: " + agentJar.getAbsolutePath());
      System.out.println("[Agent] Found Common at: " + coreJar.getAbsolutePath());
      if (!coreJar.exists()) {
        System.err.println("[Agent] FATAL ERROR: Could not find " + coreJar.getAbsolutePath());
        System.err.println("[Agent] Please ensure you are running java from the project root.");
        return;
      }
      inst.appendToBootstrapClassLoaderSearch(new JarFile(coreJar));
      inst.appendToBootstrapClassLoaderSearch((new JarFile(agentJar)));
      // 1. Setup the binary trace file (1 million events for now)
      BinarySchema.init("trace.bin", 1_000_000);
      // 3. Register bytecode surgeon (The Transformer)
      inst.addTransformer(new SyncTransformer(), true);

      System.out.println("[Agent] Instrumentation active. Recording started.");
    } catch (Exception e) {
      System.err.println("[Agent] Fatal error during initialization!");
      e.printStackTrace();
    }
  }
}
