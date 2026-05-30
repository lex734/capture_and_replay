package replay;

import common.IdentityMapper;
import common.v1.AgentRuntimeConfig;
import common.v1.ReducedTraceRegistry;
import common.v1.SemanticTraceRegistry;
import common.v1.TraceObjectId;
import instr.SyncTransformer;
import instr.StaticPrePassRegistry;

import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.Set;

public class ReplayAgent {
    public static void premain(String agentArgs, Instrumentation inst) {
        // System.out.println("[ReplayAgent] Initializing Enforcer...");

        try {
            AgentRuntimeConfig config = AgentRuntimeConfig.parse(agentArgs);
            // 1. Reset the "Brain" to ensure discovery order matches Capture
            IdentityMapper.reset();
            TraceObjectId.resetSequence();
            SemanticTraceRegistry.resetReplayState();
            ReducedTraceRegistry.reset();
            SyncTransformer.resetSiteRegistry();
            StaticPrePassRegistry.reset();

            // 2. Load the reduced replay artifact
            java.io.File reducedFile = new java.io.File("trace-reduced.tsv");
            if (!reducedFile.exists()) {
                return;
            }
            ReducedTraceRegistry.load("trace-reduced.tsv");

            // 3. Initialize the Coordinator with the data
            String fidelityOutput = System.getProperty("tool.fidelity.output");
            ReplayCoordinator.fidelityEnabled    = (fidelityOutput != null);
            ReplayCoordinator.fidelityOutputPath = fidelityOutput;
            ReplayCoordinator.init(ReducedTraceRegistry.snapshotReducedEvents());
            Set<String> loadedClassNames = new HashSet<>();
            for (Class<?> loadedClass : inst.getAllLoadedClasses()) {
                if (loadedClass != null) {
                    loadedClassNames.add(loadedClass.getName());
                }
            }
            ReplayCoordinator.prebindStableRoots(Thread.currentThread().getContextClassLoader(), loadedClassNames);

            // 3a. Install a global handler so threads that die from uncaught exceptions
            // are removed from the active-role set, preventing coordinator deadlock.
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                int roleId = IdentityMapper.getRoleId(thread.getId());
                if (roleId != -1) {
                    ReplayCoordinator.reportThreadDead(roleId);
                }
                // Print the full stack trace so it matches what capture produced.
                throwable.printStackTrace(System.err);
            });

            // 3b. Always register the fidelity shutdown hook — file write is skipped
            // when fidelityOutputPath is null, but console summary always prints.
            Runtime.getRuntime().addShutdownHook(new Thread(
                ReplayCoordinator::printFidelityReport, "fidelity-report"));

            // 3c. Register the main thread before the app starts.
            // The main thread never goes through preRegisterThread (which is only
            // called for spawned threads), so without this its role stays in
            // pendingRoles and every early event gets deadlock-skipped, racing
            // currentIdx past all events before t1/t2 even launch.
            ReplayCoordinator.registerMainThread(Thread.currentThread().getId());

            // System.out.println("[ReplayAgent] Loaded reduced replay events. Instrumentation active.");

            // 4. Add the Transformer (The mode is handled by System Property tool.mode=REPLAY)
            System.setProperty("tool.mode", "REPLAY");
            inst.addTransformer(new SyncTransformer(config), true);

        } catch (Exception e) {
            // System.err.println("[ReplayAgent] Failed to initialize:");
            e.printStackTrace();
        }
    }
}
