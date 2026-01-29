package instr;

import common.BinarySchema;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;
import java.io.File;

public class Agent {
  public static void premain(String agentArgs, Instrumentation inst) {
    try {
      System.out.println("[Agent] Initializing Recorder...");

      String agentPath = Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
      File agentJar = new File(agentPath);

      File coreJar = new File(agentPath + "/libs/trace-common.jar");
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
