package capture;

import common.BinarySchema;
import common.AgentRuntimeConfig;
import common.IdentityMapper;
import common.SemanticTraceRegistry;
import common.SyncTransformer;
import common.TraceObjectId;
import java.lang.instrument.Instrumentation;

public class CaptureAgent {
  public static void premain(String agentArgs, Instrumentation inst) {
    try {
      AgentRuntimeConfig config = AgentRuntimeConfig.parse(agentArgs);

      System.out.println("[Agent] Initializing Recorder...");
      IdentityMapper.reset();
      TraceObjectId.resetSequence();
      SemanticTraceRegistry.resetCaptureState();
      SemanticTraceRegistry.resetReplayState();
      SyncTransformer.resetSiteRegistry();
      // common is shaded into this agent jar, which is already on the system
      // classpath via -javaagent, so no separate appendToSystemClassLoaderSearch needed.
      // 1. Setup the binary trace file (1 million events for now)
      BinarySchema.init("trace.bin", 1_000_000);
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try {
          BinarySchema.flush();
          SemanticTraceRegistry.saveCaptured("trace-semantic.tsv");
          TraceReducer.reduceFieldInteractionsToFile("trace-reduced.tsv");
        } catch (Throwable e) {
          e.printStackTrace(System.err);
        }
      }, "trace-flush"));
      // 3. Register bytecode surgeon (The Transformer)
      inst.addTransformer(new SyncTransformer(config), true);

      System.out.println("[Agent] Instrumentation active. Recording started.");
    } catch (Exception e) {
      System.err.println("[Agent] Fatal error during initialization!");
      e.printStackTrace();
    }
  }
}
