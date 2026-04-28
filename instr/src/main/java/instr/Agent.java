package instr;

import common.BinarySchema;
import common.IdentityMapper;
import java.lang.instrument.Instrumentation;

public class Agent {
  private static final boolean VERBOSE_STDOUT = Boolean.getBoolean("trace.capture.verbose");
  private static final long DEFAULT_MAX_EVENTS = 1_000_000L;

  private static void debug(String message) {
    if (!VERBOSE_STDOUT) return;
    System.out.println(message);
  }

  public static void premain(String agentArgs, Instrumentation inst) {
    try {
      // Parse optional agent arguments: exclude=<package/prefix>
      String extraExclude = null;
      String includePrefix = null;
      String traceFile = "trace.bin";
      long maxEvents = DEFAULT_MAX_EVENTS;
      if (agentArgs != null) {
        for (String part : agentArgs.split(",")) {
          part = part.trim();
          if (part.startsWith("exclude=")) {
            extraExclude = part.substring("exclude=".length()).replace('.', '/');
          } else if (part.startsWith("include=")) {
            String v = part.substring("include=".length()).trim();
            if (!v.isEmpty()) includePrefix = v.replace('.', '/');
          } else if (part.startsWith("trace=")) {
            String v = part.substring("trace=".length()).trim();
            if (!v.isEmpty()) traceFile = v;
          } else if (part.startsWith("maxEvents=")) {
            String v = part.substring("maxEvents=".length()).trim();
            if (!v.isEmpty()) {
              try {
                maxEvents = Long.parseLong(v);
              } catch (NumberFormatException ignored) {
                maxEvents = DEFAULT_MAX_EVENTS;
              }
            }
          }
        }
      }

      debug("[Agent] Initializing Recorder...");
      IdentityMapper.reset();
      SyncTransformer.resetSiteRegistry();
      // common is shaded into this agent jar, which is already on the system
      // classpath via -javaagent, so no separate appendToSystemClassLoaderSearch needed.
      // 1. Setup the binary trace file (1 million events for now)
      BinarySchema.init(traceFile, maxEvents);
      // 3. Register bytecode surgeon (The Transformer)
      inst.addTransformer(new SyncTransformer(extraExclude, includePrefix), true);

      debug("[Agent] Instrumentation active. Recording started.");
    } catch (Exception e) {
      System.err.println("[Agent] Fatal error during initialization!");
      e.printStackTrace();
    }
  }
}
