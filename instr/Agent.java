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
      SyncTransformer.resetSiteRegistry();
      java.net.URI agentUri = Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI();
      File agentJar = new File(agentUri);

      // Get the 'libs' folder (the parent of the agent jar)
      File libsDir = agentJar.getParentFile();

      // Locate the common JAR in that same 'libs' folder
      File coreJar = new File(libsDir, "trace-common.jar");

      System.out.println("[Agent] Found Agent at: " + agentJar.getAbsolutePath());
      System.out.println("[Agent] Found Common at: " + coreJar.getAbsolutePath());
      if (!coreJar.exists()) {
        System.err.println("[Agent] FATAL ERROR: Could not find " + coreJar.getAbsolutePath());
        System.err.println("[Agent] Please ensure you are running java from the project root.");
        return;
      }
      // Add common to the system classloader only. The agent JAR is already on
      // the system classpath (Java loads -javaagent premain classes via the
      // system classloader). Adding it to bootstrap as well would cause ASM
      // classes to be loaded by two different classloaders, triggering
      // IllegalAccessError due to Java 9+ unnamed-module isolation.
      inst.appendToSystemClassLoaderSearch(new JarFile(coreJar));
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
