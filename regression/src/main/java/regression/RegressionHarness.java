package regression;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Static facade for the capture and regression phases. */
public final class RegressionHarness {

    private RegressionHarness() {}

    public static void capture(String targetClass, String captureAgentJar,
                               String appJar, String regressionJar,
                               Path traceStore, int runs) throws Exception {
        RegressionRunner.run(targetClass, captureAgentJar, appJar, regressionJar, traceStore, runs);
    }

    public static void regress(String targetClass, String replayAgentJar,
                               String appJar, String regressionJar,
                               Path traceStore) throws Exception {
        RegressionReplayer.run(targetClass, replayAgentJar, appJar, regressionJar, traceStore);
    }

    public static Path defaultTraceStore() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "regression-traces");
    }
}
