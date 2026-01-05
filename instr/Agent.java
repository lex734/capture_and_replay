package instr;

import core.BinarySchema;
import core.CaptureMonitor;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;
import java.io.File;

public class Agent {
  public static void premain(String agentArgs, Instrumentation inst) {
    try {
      System.out.println("[Agent] Initializing Recorder...");

      File coreJar = new File("libs/trace-core.jar");
      if (!coreJar.exists()) {
        System.err.println("[Agent] FATAL ERROR: Could not find " + coreJar.getAbsolutePath());
        System.err.println("[Agent] Please ensure yo7u are running java from the project root.");
        return;
      }
      inst.appendToBootstrapClassLoaderSearch(new JarFile(coreJar));
      // 1. Setup the binary trace file (1 million events for now)
      BinarySchema.init("trace.bin", 1_000_000);

      Runtime.getRuntime().addShutdownHook(new Thread() -> {
        System.out.println("\n[Agent] Program exiting. Finalizing trace file...");
        BinarySchema.close();
        System.out.println("[Agent] Trace saved to trace.bin");
      })
      // 3. Register bytecode surgeon (The Transformer)
      inst.addTransformer(new SyncTransformer(), true);

      System.out.println("[Agent] Instrumentation active. Recording started.");
    } catch (Exception e) {
      System.err.println("[Agent] Fatal error during initialization!");
      e.printStackTrace();
    }
  }
}
