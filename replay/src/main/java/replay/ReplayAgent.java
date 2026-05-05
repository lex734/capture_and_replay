package replay;

import common.BinarySchema;
import common.IdentityMapper;
import common.ReplayBoundaryRegistry;
import instr.SyncTransformer;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.instrument.Instrumentation;
import java.nio.channels.FileChannel;
import java.nio.MappedByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ReplayAgent {
    private static final String STATIC_ANALYSIS_PATH_PROPERTY = "tool.static.analysis.path";
    private static final String STATIC_ANALYSIS_STATUS_PROPERTY = "tool.static.analysis.status";
    private static final String STATIC_ANALYSIS_REASON_PROPERTY = "tool.static.analysis.reason";

    public static void premain(String agentArgs, Instrumentation inst) {
        // System.out.println("[ReplayAgent] Initializing Enforcer...");

        try {
            // 1. Reset the "Brain" to ensure discovery order matches Capture
            IdentityMapper.reset();
            ReplayBoundaryRegistry.reset();
            SyncTransformer.resetSiteRegistry();

            // 2. Load the Trace File
            File traceFile = new File("trace.bin");
            if (!traceFile.exists()) {
                // System.err.println("[ReplayAgent] ERROR: trace.bin not found!");
                return;
            }

            // 2a. Distill the schedule artifact and run the mandatory static analysis
            Path tracePath = traceFile.toPath();
            Path boundariesPath = ScheduleDistiller.DEFAULT_BOUNDARIES;
            Path schedulePath = ScheduleDistiller.DEFAULT_OUTPUT;
            List<ScheduleDistiller.ScheduleEntry> scheduleEntries =
                    ScheduleDistiller.distill(tracePath, boundariesPath);
            ScheduleDistiller.write(schedulePath, scheduleEntries);
            ReplayCoordinator.loadScheduleArtifact(scheduleEntries);
            IdentityMapper.setAllowedReplayRoles(extractScheduleRoles(scheduleEntries));

            Path analysisPath = resolveStaticAnalysisPath();
            ScheduleStaticAnalyzer.Result staticResult =
                    ScheduleStaticAnalyzer.analyze(schedulePath, analysisPath);
            System.setProperty(STATIC_ANALYSIS_STATUS_PROPERTY, staticResult.status.name());
            if (staticResult.status != ScheduleStaticAnalyzer.Status.APPLICABLE) {
                String firstReason = staticResult.reasons.isEmpty()
                        ? "unknown static incompatibility"
                        : staticResult.reasons.get(0);
                System.setProperty(STATIC_ANALYSIS_REASON_PROPERTY, firstReason);
                System.err.println("[STATIC] Replay is statically inapplicable.");
                for (String reason : staticResult.reasons) {
                    System.err.println("[STATIC] " + reason);
                }
                return;
            }
            System.clearProperty(STATIC_ANALYSIS_REASON_PROPERTY);

            long fileSize = traceFile.length();
            long totalEvents = fileSize / BinarySchema.RECORD_SIZE;

            RandomAccessFile raf = new RandomAccessFile(traceFile, "r");
            MappedByteBuffer buffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            raf.close();

            // 3. Initialize the Coordinator with the data
            ReplayCoordinator.init(buffer, totalEvents);
            ReplayCoordinator.loadScheduleArtifact(scheduleEntries);

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

            // 3b. Register the main thread before the app starts.
            // The main thread never goes through preRegisterThread (which is only
            // called for spawned threads), so without this its role stays pending
            // and the earliest replay boundaries can never be admitted.
            ReplayCoordinator.registerMainThread(Thread.currentThread().getId());

            // System.out.println("[ReplayAgent] Loaded " + totalEvents + " events. Instrumentation active.");

            // 4. Add the Transformer (The mode is handled by System Property tool.mode=REPLAY)
            System.setProperty("tool.mode", "REPLAY");
            inst.addTransformer(new SyncTransformer(), true);

        } catch (Exception e) {
            // System.err.println("[ReplayAgent] Failed to initialize:");
            e.printStackTrace();
        }
    }

    private static Path resolveStaticAnalysisPath() {
        String explicit = System.getProperty(STATIC_ANALYSIS_PATH_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }

        String classPath = System.getProperty("java.class.path", "");
        String[] rawEntries = classPath.split(File.pathSeparator);
        List<Path> existingEntries = new ArrayList<>();
        List<Path> jarEntries = new ArrayList<>();
        List<Path> directoryEntries = new ArrayList<>();

        for (String rawEntry : rawEntries) {
            if (rawEntry == null || rawEntry.isBlank()) continue;
            Path entry = Path.of(rawEntry);
            if (!entry.toFile().exists()) continue;
            existingEntries.add(entry);
            if (rawEntry.endsWith(".jar")) {
                jarEntries.add(entry);
            } else if (entry.toFile().isDirectory()) {
                directoryEntries.add(entry);
            }
        }

        if (existingEntries.size() == 1) {
            return existingEntries.get(0);
        }
        if (jarEntries.size() == 1) {
            return jarEntries.get(0);
        }
        if (directoryEntries.size() == 1) {
            return directoryEntries.get(0);
        }

        throw new IllegalStateException(
                "Unable to resolve mandatory static-analysis path from java.class.path; "
                        + "set -D" + STATIC_ANALYSIS_PATH_PROPERTY + "=<jar-or-classes-dir>");
    }

    private static Set<Integer> extractScheduleRoles(List<ScheduleDistiller.ScheduleEntry> scheduleEntries) {
        HashSet<Integer> roleIds = new HashSet<>();
        for (ScheduleDistiller.ScheduleEntry entry : scheduleEntries) {
            roleIds.add(entry.roleId);
        }
        return roleIds;
    }
}
