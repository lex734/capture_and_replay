package regression.core;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Subprocess launcher adapted from FidelityBenchmark. */
public final class ProcessRunner {

    private static final String REDUCED_TRACE_PROPERTY = "tool.reduced.trace";
    private static final String FIDELITY_OUTPUT_PROPERTY = "tool.fidelity.output";

    private ProcessRunner() {}

    public static RunResult runCapture(String captureAgentJar, String appJar,
                                       String regressionJar, String targetClass,
                                       Path workDir, long timeoutMs)
            throws IOException, InterruptedException {

        String agent = abs(captureAgentJar);
        String cp    = abs(appJar) + File.pathSeparator + abs(regressionJar);
        List<String> args = Arrays.asList(
            "-javaagent:" + agent,
            "-ea",
            "-cp", cp,
            "-Dregression.target.class=" + targetClass,
            "regression.ObserverMain"
        );
        return runJava(args, workDir, timeoutMs);
    }

    public static RunResult runReplay(String replayAgentJar, String appJar,
                                      String regressionJar, String targetClass,
                                      Path workDir, Path reducedTrace, Path fidelityOutput,
                                      long timeoutMs)
            throws IOException, InterruptedException {

        String agent = abs(replayAgentJar);
        String cp    = abs(appJar) + File.pathSeparator + abs(regressionJar);
        List<String> args = Arrays.asList(
            "-D" + REDUCED_TRACE_PROPERTY + "=" + reducedTrace.toAbsolutePath(),
            "-D" + FIDELITY_OUTPUT_PROPERTY + "=" + fidelityOutput.toAbsolutePath(),
            "-javaagent:" + agent,
            "-ea",
            "-cp", cp,
            "-Dregression.target.class=" + targetClass,
            "regression.ObserverMain"
        );
        return runJava(args, workDir, timeoutMs);
    }

    private static String abs(String path) {
        return Paths.get(path).toAbsolutePath().toString();
    }

    private static RunResult runJava(List<String> extraArgs, Path workDir, long timeoutMs)
            throws IOException, InterruptedException {

        List<String> cmd = new ArrayList<>(Arrays.asList(
            javaExecutable(),
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-opens", "java.base/java.util.concurrent=ALL-UNNAMED",
            "--add-opens", "java.base/java.util.concurrent.locks=ALL-UNNAMED"
        ));
        cmd.addAll(extraArgs);

        Process p = new ProcessBuilder(cmd)
            .directory(workDir.toFile())
            .redirectErrorStream(false)
            .start();

        StringWriter outSW = new StringWriter(), errSW = new StringWriter();
        Thread outT = drain(p.getInputStream(), outSW);
        Thread errT = drain(p.getErrorStream(), errSW);

        boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroy();
            finished = p.waitFor(2, TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            p.waitFor();
        }
        outT.join();
        errT.join();

        return new RunResult(outSW.toString(), errSW.toString(),
            finished ? p.exitValue() : 124, !finished);
    }

    private static Thread drain(InputStream is, Writer out) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = br.readLine()) != null) out.write(line + "\n");
            } catch (IOException ignored) {}
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isEmpty()) {
            Path candidate = Paths.get(javaHome, "bin", "java");
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return "java";
    }

    public static void deleteRecursivelyIfExists(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
