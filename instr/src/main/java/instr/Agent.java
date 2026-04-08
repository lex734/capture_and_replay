package instr;

import common.BinarySchema;
import common.IdentityMapper;
import java.lang.instrument.Instrumentation;

public class Agent {
  public static void premain(String agentArgs, Instrumentation inst) {
    try {
      // Parse optional agent arguments: exclude=<package/prefix>
      String extraExclude = null;
      if (agentArgs != null) {
        for (String part : agentArgs.split(",")) {
          if (part.startsWith("exclude=")) {
            extraExclude = part.substring("exclude=".length()).replace('.', '/');
          }
        }
      }

      System.out.println("[Agent] Initializing Recorder...");
      IdentityMapper.reset();
      SyncTransformer.resetSiteRegistry();
      // common is shaded into this agent jar, which is already on the system
      // classpath via -javaagent, so no separate appendToSystemClassLoaderSearch needed.
      // 1. Setup the binary trace file (1 million events for now)
      // System.out.println("[Agent] Writing trace to: " + new java.io.File("trace.bin").getAbsolutePath());
      BinarySchema.init("trace.bin", 1_000_000);
      // 3. Register bytecode surgeon (The Transformer)
      inst.addTransformer(new SyncTransformer(extraExclude), true);

      // System.out.println("[Agent] Instrumentation active. Recording started.");
    } catch (Exception e) {
      System.err.println("[Agent] Fatal error during initialization!");
      e.printStackTrace();
    }
  }
}
