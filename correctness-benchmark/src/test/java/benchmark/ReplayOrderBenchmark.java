package benchmark;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctness benchmark: for each test program, run capture once and then replay
 * {@value #REPLAY_RUNS} times, asserting that every single replay:
 *
 * <ol>
 *   <li>Matched the same number of events as the capture trace (no missing events).</li>
 *   <li>Skipped zero events (no role died or deadlocked).</li>
 *   <li>Produced the same sequence hash as the capture trace — verifying that the
 *       {@code (roleId, eventType)} pairs were consumed in exactly the captured
 *       total order without printing individual event logs.</li>
 * </ol>
 *
 * <h2>Sequence hash</h2>
 * The hash is a simple polynomial rolling hash computed over the ordered sequence
 * of {@code (roleId, eventType)} pairs:
 * <pre>
 *   h = 17
 *   for each event in total order:
 *       h = h * 31 + (roleId  &amp; 0xFFFF)
 *       h = h * 31 + (type    &amp; 0xFF)
 * </pre>
 * The capture-side hash is computed here by parsing {@code trace.bin} directly
 * (records are 48-byte fixed-size; seq at byte 0, roleId at byte 8, packedType at
 * byte 16). The replay-side hash is emitted by {@code ReplayCoordinator.printStats()}
 * to stderr as part of the {@code [ReplayStats]} line.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ReplayOrderBenchmark {

    static final int REPLAY_RUNS = 100;

    /** Trace record layout (48 bytes). */
    private static final int RECORD_SIZE = 48;
    private static final int OFF_SEQ       = 0;
    private static final int OFF_ROLE_ID   = 8;
    private static final int OFF_TYPE      = 16;

    private static final Pattern STATS_PATTERN = Pattern.compile(
        "\\[ReplayStats\\] matched=(\\d+) skipped=(\\d+) total=(\\d+) seqHash=(-?\\d+)");

    private File repoRoot;
    private File captureAgentJar;
    private File replayAgentJar;
    private File testAppJar;
    private File traceFile;

    // ------------------------------------------------------------------
    // Build once before all parameterised test cases run.
    // ------------------------------------------------------------------

    @BeforeAll
    void buildProject() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        repoRoot = moduleDir.getParentFile();
        traceFile = new File(repoRoot, "trace.bin");

        ProcResult buildRes = runCommand(List.of("mvn", "-DskipTests", "package"), repoRoot, 300);
        if (buildRes.exitCode != 0) {
            fail("Maven build failed:\n" + buildRes.stderr + "\n" + buildRes.stdout);
        }

        captureAgentJar = new File(repoRoot, "capture/target/trace-capture-agent.jar");
        replayAgentJar  = new File(repoRoot, "replay/target/trace-replay-agent.jar");
        testAppJar      = new File(repoRoot, "test-app/target/test-app.jar");

        assertTrue(captureAgentJar.exists(), "capture agent jar not found: " + captureAgentJar);
        assertTrue(replayAgentJar.exists(),  "replay agent jar not found: "  + replayAgentJar);
        assertTrue(testAppJar.exists(),       "test-app jar not found: "      + testAppJar);
    }

    // ------------------------------------------------------------------
    // Parameterised benchmark — one invocation per class.
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "correctness.AtomicCounterTest",
        "correctness.PlainCounterTest",
        "correctness.VolatileWriteRaceTest",
        "correctness.SharedObjectRaceTest",
        "correctness.ArrayElementRaceTest",
        "correctness.LinkedListContentionTest",
    })
    void replayOrderIsReproducible(String mainClass) throws Exception {

        // ── 1. Capture ────────────────────────────────────────────────────
        List<String> captureCmd = buildCmd(captureAgentJar, mainClass);
        ProcResult capRes = runCommand(captureCmd, repoRoot, 120);
        if (capRes.exitCode != 0) {
            fail("[" + mainClass + "] Capture failed:\n" + capRes.stderr + "\n" + capRes.stdout);
        }
        assertTrue(traceFile.exists() && traceFile.length() > 0,
            "[" + mainClass + "] trace.bin missing or empty after capture");

        // ── 2. Compute expected values from trace.bin ─────────────────────
        long[] captureInfo = computeTraceInfo(traceFile);
        long captureTotal    = captureInfo[0];
        long captureSeqHash  = captureInfo[1];

        assertTrue(captureTotal > 0,
            "[" + mainClass + "] trace.bin contains no events");

        // ── 3. Run REPLAY_RUNS replays and check each one ─────────────────
        for (int run = 1; run <= REPLAY_RUNS; run++) {
            List<String> replayCmd = buildCmd(replayAgentJar, mainClass);
            ProcResult repRes = runCommand(replayCmd, repoRoot, 120);

            String runLabel = "[" + mainClass + "] run " + run + "/" + REPLAY_RUNS;

            if (repRes.exitCode != 0) {
                fail(runLabel + " – replay process exited with code " + repRes.exitCode
                    + "\nstderr:\n" + repRes.stderr
                    + "\nstdout:\n" + repRes.stdout);
            }

            long[] stats = extractStats(repRes.stderr);
            assertNotNull(stats,
                runLabel + " – [ReplayStats] line not found in stderr.\n"
                + "stderr:\n" + repRes.stderr);

            long matched  = stats[0];
            long skipped  = stats[1];
            long total    = stats[2];
            long seqHash  = stats[3];

            assertEquals(0L, skipped,
                runLabel + " – " + skipped + " event(s) were skipped "
                + "(a role died or never started).\n"
                + "matched=" + matched + " total=" + total);

            assertEquals(captureTotal, matched,
                runLabel + " – matched " + matched + " events but capture had "
                + captureTotal + ".\nstderr:\n" + repRes.stderr);

            assertEquals(captureSeqHash, seqHash,
                runLabel + " – sequence hash mismatch: replay hash=" + seqHash
                + " capture hash=" + captureSeqHash
                + "\nThis means the (roleId, eventType) sequence differed from the capture.");
            System.out.println(runLabel + " – success: matched=" + matched + " seqHash=" + seqHash);
        }
    }

    // ------------------------------------------------------------------
    // Trace parsing helpers
    // ------------------------------------------------------------------

    /**
     * Reads trace.bin and returns {@code [totalEvents, sequenceHash]}.
     *
     * The hash is computed as:
     * <pre>
     *   h = 17
     *   for each non-empty record sorted by seq:
     *       h = h * 31 + (roleId &amp; 0xFFFF)
     *       h = h * 31 + (eventType &amp; 0xFF)   // lower byte of packedType
     * </pre>
     */
    private long[] computeTraceInfo(File trace) throws IOException {
        long fileSize = trace.length();
        if (fileSize == 0) return new long[]{0L, 17L};

        ByteBuffer buf;
        try (FileChannel ch = FileChannel.open(trace.toPath(), StandardOpenOption.READ)) {
            buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
        }
        // BinarySchema uses MappedByteBuffer's default big-endian order — do NOT
        // override to nativeOrder here or longs/ints will be read byte-swapped.

        // Collect non-empty records
        int maxSlots = (int) (fileSize / RECORD_SIZE);
        List<long[]> records = new ArrayList<>(maxSlots);
        for (int i = 0; i < maxSlots; i++) {
            int pos = i * RECORD_SIZE;
            long seq        = buf.getLong(pos + OFF_SEQ);
            long roleId     = buf.getLong(pos + OFF_ROLE_ID);
            int  packedType = buf.getInt (pos + OFF_TYPE);
            // Skip zero-filled batch-tail slots
            if (seq == 0 && roleId == 0 && packedType == 0) continue;
            records.add(new long[]{seq, roleId, packedType});
        }

        // Sort by seq (total order)
        records.sort((a, b) -> Long.compare(a[0], b[0]));

        // Rolling hash — must match ReplayCoordinator.printStats()
        long h = 17L;
        for (long[] r : records) {
            h = h * 31L + (r[1] & 0xFFFFL); // roleId low 16 bits
            h = h * 31L + (r[2] & 0xFFL);   // eventType byte
        }

        return new long[]{records.size(), h};
    }

    // ------------------------------------------------------------------
    // Stats parsing helpers
    // ------------------------------------------------------------------

    /** Returns [matched, skipped, total, seqHash] parsed from the [ReplayStats] line, or null. */
    private static long[] extractStats(String stderr) {
        if (stderr == null) return null;
        try (BufferedReader br = new BufferedReader(new StringReader(stderr))) {
            String line;
            while ((line = br.readLine()) != null) {
                Matcher m = STATS_PATTERN.matcher(line);
                if (m.find()) {
                    return new long[]{
                        Long.parseLong(m.group(1)), // matched
                        Long.parseLong(m.group(2)), // skipped
                        Long.parseLong(m.group(3)), // total
                        Long.parseLong(m.group(4))  // seqHash
                    };
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    // ------------------------------------------------------------------
    // Process helpers
    // ------------------------------------------------------------------

    private List<String> buildCmd(File agentJar, String mainClass) {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-javaagent:" + agentJar.getAbsolutePath());
        cmd.add("-cp");
        cmd.add(testAppJar.getAbsolutePath());
        cmd.add(mainClass);
        return cmd;
    }

    private static class ProcResult {
        int exitCode;
        String stdout;
        String stderr;
    }

    private ProcResult runCommand(List<String> cmd, File workdir, long timeoutSeconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workdir);
        pb.redirectErrorStream(false);
        Process p = pb.start();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        Thread tout = new Thread(() -> {
            try (InputStream is = p.getInputStream()) { is.transferTo(out); }
            catch (IOException ignored) {}
        });
        Thread terr = new Thread(() -> {
            try (InputStream is = p.getErrorStream()) { is.transferTo(err); }
            catch (IOException ignored) {}
        });
        tout.start();
        terr.start();

        boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new RuntimeException("Command timed out: " + String.join(" ", cmd));
        }
        tout.join(1_000);
        terr.join(1_000);

        ProcResult r = new ProcResult();
        r.exitCode = p.exitValue();
        r.stdout   = out.toString(StandardCharsets.UTF_8.name());
        r.stderr   = err.toString(StandardCharsets.UTF_8.name());
        return r;
    }
}
