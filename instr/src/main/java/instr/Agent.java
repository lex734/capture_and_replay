package instr;

import common.BinarySchema;
import common.IdentityMapper;
import common.v1.AgentRuntimeConfig;
import common.v1.TraceReducer;
import common.v1.SemanticTraceRegistry;
import common.v1.TraceObjectId;
import java.lang.instrument.Instrumentation;

public class Agent {
  public static void premain(String agentArgs, Instrumentation inst) {
    try {
      AgentRuntimeConfig config = AgentRuntimeConfig.parse(agentArgs);

      System.out.println("[Agent] Initializing Recorder...");
      IdentityMapper.reset();
      TraceObjectId.resetSequence();
      SemanticTraceRegistry.resetCaptureState();
      SemanticTraceRegistry.resetReplayState();
      SyncTransformer.resetSiteRegistry();
      StaticPrePassRegistry.reset();
      // common is shaded into this agent jar, which is already on the system
      // classpath via -javaagent, so no separate appendToSystemClassLoaderSearch needed.
      // 1. Setup the binary trace file (1 million events for now)
      BinarySchema.init("trace.bin", 1_000_000);
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try {
          BinarySchema.flush();
          SemanticTraceRegistry.saveCaptured("trace-semantic.tsv");
          TraceReducer.reduceFieldInteractionsToFile("trace-reduced.tsv");
          SemanticTraceRegistry.resetCaptureState();
          SemanticTraceRegistry.resetReplayState();
        } catch (Exception e) {
          e.printStackTrace(System.err);
        }
      }, "trace-reducer"));
      // 3. Register bytecode surgeon (The Transformer)
      inst.addTransformer(new SyncTransformer(config), true);

      System.out.println("[Agent] Instrumentation active. Recording started.");
    } catch (Exception e) {
      System.err.println("[Agent] Fatal error during initialization!");
      e.printStackTrace();
    }
  }
}
