package replay;

import common.BinarySchema;
import common.IdentityMapper;
import common.v1.AgentRuntimeConfig;
import common.v1.TraceObjectId;
import instr.SyncTransformer;
import instr.StaticPrePassRegistry;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.instrument.Instrumentation;
import java.nio.channels.FileChannel;
import java.nio.MappedByteBuffer;

public class ReplayAgent {
    public static void premain(String agentArgs, Instrumentation inst) {
        // System.out.println("[ReplayAgent] Initializing Enforcer...");

        try {
            AgentRuntimeConfig config = AgentRuntimeConfig.parse(agentArgs);
            // 1. Reset the "Brain" to ensure discovery order matches Capture
            IdentityMapper.reset();
            TraceObjectId.resetSequence();
            SyncTransformer.resetSiteRegistry();
            StaticPrePassRegistry.reset();

            // 2. Load the Trace File
            File traceFile = new File("trace.bin");
            if (!traceFile.exists()) {
                // System.err.println("[ReplayAgent] ERROR: trace.bin not found!");
                return;
            }

            long fileSize = traceFile.length();
            long totalEvents = fileSize / BinarySchema.RECORD_SIZE;

            RandomAccessFile raf = new RandomAccessFile(traceFile, "r");
            MappedByteBuffer buffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            raf.close();

            // 3. Initialize the Coordinator with the data
            String fidelityOutput = System.getProperty("tool.fidelity.output");
            ReplayCoordinator.fidelityEnabled    = (fidelityOutput != null);
            ReplayCoordinator.fidelityOutputPath = fidelityOutput;
            ReplayCoordinator.init(buffer, totalEvents);

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

            // 3b. Register fidelity shutdown hook if enabled.
            if (ReplayCoordinator.fidelityEnabled) {
                Runtime.getRuntime().addShutdownHook(new Thread(
                    ReplayCoordinator::printFidelityReport, "fidelity-report"));
            }

            // 3c. Register the main thread before the app starts.
            // The main thread never goes through preRegisterThread (which is only
            // called for spawned threads), so without this its role stays in
            // pendingRoles and every early event gets deadlock-skipped, racing
            // currentIdx past all events before t1/t2 even launch.
            ReplayCoordinator.registerMainThread(Thread.currentThread().getId());

            // System.out.println("[ReplayAgent] Loaded " + totalEvents + " events. Instrumentation active.");

            // 4. Add the Transformer (The mode is handled by System Property tool.mode=REPLAY)
            System.setProperty("tool.mode", "REPLAY");
            inst.addTransformer(new SyncTransformer(config), true);

        } catch (Exception e) {
            // System.err.println("[ReplayAgent] Failed to initialize:");
            e.printStackTrace();
        }
    }
}
