package capture_and_replay.tests;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctness harness: for each test program, run capture (with the
 * instrumentation agent) then replay (with the replay agent) and assert that
 * the printed final state is identical.
 *
 * All programs live in the {@code correctness} package under
 * {@code test-app/src/main/java/correctness/} and follow two conventions:
 *   1. They have a standard {@code public static void main(String[] args)} entry point.
 *   2. They print exactly one line that contains the word "Final" (case-insensitive)
 *      to stdout — this line is used for comparison.
 *
 * The Maven build is performed once in {@code @BeforeAll} so that adding a new
 * test program only requires:
 *   a) creating the class in the {@code correctness} package, and
 *   b) adding its fully-qualified name to the {@code @ValueSource} below.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DeterminismProcessTest {

    // -----------------------------------------------------------------------
    // Register test programs here — one entry per class.
    // -----------------------------------------------------------------------
    private static final String[] TEST_CLASSES = {
        "correctness.AtomicCounterTest",
        "correctness.PlainCounterTest",
        "correctness.VolatileWriteRaceTest",
        "correctness.SharedObjectRaceTest",
        "correctness.ArrayElementRaceTest",
    };

    private File repoRoot;
    private File captureAgentJar;
    private File replayAgentJar;
    private File testAppJar;

    // ------------------------------------------------------------------
    // Build once before all parameterized test cases run.
    // ------------------------------------------------------------------

    @BeforeAll
    void buildProject() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        repoRoot = moduleDir.getParentFile();

        ProcResult buildRes = runCommand(List.of("mvn", "-DskipTests", "package"), repoRoot, 300);
        if (buildRes.exitCode != 0) {
            fail("Maven build failed:\n" + buildRes.stderr + "\n" + buildRes.stdout);
        }

        captureAgentJar = new File(repoRoot, "capture/target/trace-capture-agent.jar");
        replayAgentJar  = new File(repoRoot, "replay/target/trace-replay-agent.jar");
        testAppJar       = new File(repoRoot, "test-app/target/test-app.jar");

        assertTrue(captureAgentJar.exists(), "capture agent jar not found: " + captureAgentJar);
        assertTrue(replayAgentJar.exists(),  "replay agent jar not found: "  + replayAgentJar);
        assertTrue(testAppJar.exists(),       "test-app jar not found: "       + testAppJar);
    }

    // ------------------------------------------------------------------
    // Parameterized test — one invocation per class in TEST_CLASSES.
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "correctness.AtomicCounterTest",
        "correctness.PlainCounterTest",
        "correctness.VolatileWriteRaceTest",
        "correctness.SharedObjectRaceTest",
        "correctness.ArrayElementRaceTest",
    })
    void captureThenReplay_finalStateMatches(String mainClass) throws Exception {
        // 1) Capture
        List<String> captureCmd = new ArrayList<>();
        captureCmd.add("java");
        captureCmd.add("-javaagent:" + captureAgentJar.getAbsolutePath());
        captureCmd.add("-cp");
        captureCmd.add(testAppJar.getAbsolutePath());
        captureCmd.add(mainClass);

        ProcResult capRes = runCommand(captureCmd, repoRoot, 120);
        if (capRes.exitCode != 0) {
            fail("[" + mainClass + "] Capture failed:\n" + capRes.stderr + "\n" + capRes.stdout);
        }

        String capFinal = extractFinalLine(capRes.stdout);
        assertNotNull(capFinal,
            "[" + mainClass + "] No 'Final' line in capture stdout:\n" + capRes.stdout);

        // 2) Replay
        List<String> replayCmd = new ArrayList<>();
        replayCmd.add("java");
        replayCmd.add("-javaagent:" + replayAgentJar.getAbsolutePath());
        replayCmd.add("-cp");
        replayCmd.add(testAppJar.getAbsolutePath());
        replayCmd.add(mainClass);

        ProcResult repRes = runCommand(replayCmd, repoRoot, 120);
        if (repRes.exitCode != 0) {
            fail("[" + mainClass + "] Replay failed:\n" + repRes.stderr + "\n" + repRes.stdout);
        }

        String repFinal = extractFinalLine(repRes.stdout);
        assertNotNull(repFinal,
            "[" + mainClass + "] No 'Final' line in replay stdout:\n" + repRes.stdout);

        // 3) Compare
        assertEquals(capFinal.trim(), repFinal.trim(),
            "[" + mainClass + "] Final state differs between capture and replay\n"
            + "Capture: " + capFinal + "\nReplay:  " + repFinal);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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
            catch (IOException ignored) { }
        });
        Thread terr = new Thread(() -> {
            try (InputStream is = p.getErrorStream()) { is.transferTo(err); }
            catch (IOException ignored) { }
        });
        tout.start();
        terr.start();

        boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new RuntimeException("Command timed out: " + String.join(" ", cmd));
        }
        tout.join(1000);
        terr.join(1000);

        ProcResult r = new ProcResult();
        r.exitCode = p.exitValue();
        r.stdout   = out.toString(StandardCharsets.UTF_8.name());
        r.stderr   = err.toString(StandardCharsets.UTF_8.name());
        return r;
    }

    /**
     * Returns the last line in {@code stdout} that contains the word "final"
     * (case-insensitive). Each test program must print exactly one such line.
     */
    private String extractFinalLine(String stdout) {
        if (stdout == null) return null;
        try (BufferedReader br = new BufferedReader(new StringReader(stdout))) {
            String line, last = null;
            while ((line = br.readLine()) != null) {
                if (line.toLowerCase().contains("final")) last = line;
            }
            return last;
        } catch (IOException e) {
            return null;
        }
    }
}
