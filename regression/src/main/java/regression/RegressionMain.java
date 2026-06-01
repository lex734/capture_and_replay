package regression;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI entry point.
 *
 * <pre>
 * # Capture phase
 * java -jar regression.jar capture \
 *   --class &lt;fqcn&gt; \
 *   --capture-agent &lt;path&gt; \
 *   --app-jar &lt;path&gt; \
 *   [--regression-jar &lt;path&gt;]   (default: this jar)
 *   [--trace-store &lt;path&gt;]      (default: java.io.tmpdir/regression-traces)
 *   [--runs &lt;n&gt;]
 *
 * # Regression phase
 * java -jar regression.jar regress \
 *   --class &lt;fqcn&gt; \
 *   --replay-agent &lt;path&gt; \
 *   --app-jar &lt;path&gt; \
 *   [--regression-jar &lt;path&gt;]
 *   [--trace-store &lt;path&gt;]
 * </pre>
 */
public final class RegressionMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        String mode = args[0];
        Args a = Args.parse(args, 1);

        if (a.targetClass == null) {
            System.err.println("Missing required --class argument");
            System.exit(1);
        }

        Path traceStore = a.traceStore != null
            ? Paths.get(a.traceStore)
            : RegressionHarness.defaultTraceStore();

        String regressionJar = a.regressionJar != null
            ? a.regressionJar
            : selfJarPath();

        switch (mode) {
            case "capture": {
                if (a.captureAgentJar == null || a.appJar == null) {
                    System.err.println("capture requires --capture-agent and --app-jar");
                    System.exit(1);
                }
                RegressionHarness.capture(a.targetClass, a.captureAgentJar,
                    a.appJar, regressionJar, traceStore, a.runs);
                break;
            }
            case "regress": {
                if (a.replayAgentJar == null || a.appJar == null) {
                    System.err.println("regress requires --replay-agent and --app-jar");
                    System.exit(1);
                }
                RegressionHarness.regress(a.targetClass, a.replayAgentJar,
                    a.appJar, regressionJar, traceStore);
                break;
            }
            default:
                System.err.println("Unknown mode: " + mode + " (expected: capture | regress)");
                System.exit(1);
        }
    }

    private static String selfJarPath() {
        try {
            return RegressionMain.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI().getPath();
        } catch (Exception e) {
            throw new RuntimeException("Cannot determine self jar path; pass --regression-jar explicitly", e);
        }
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  regression.jar capture --class <fqcn> --capture-agent <jar> --app-jar <jar> [--trace-store <dir>] [--runs <n>]");
        System.err.println("  regression.jar regress --class <fqcn> --replay-agent  <jar> --app-jar <jar> [--trace-store <dir>]");
    }

    private static final class Args {
        String targetClass;
        String captureAgentJar;
        String replayAgentJar;
        String appJar;
        String regressionJar;
        String traceStore;
        int runs = 0;

        static Args parse(String[] argv, int start) {
            Args a = new Args();
            for (int i = start; i < argv.length; i++) {
                switch (argv[i]) {
                    case "--class":           a.targetClass     = argv[++i]; break;
                    case "--capture-agent":   a.captureAgentJar = argv[++i]; break;
                    case "--replay-agent":    a.replayAgentJar  = argv[++i]; break;
                    case "--app-jar":         a.appJar          = argv[++i]; break;
                    case "--regression-jar":  a.regressionJar   = argv[++i]; break;
                    case "--trace-store":     a.traceStore      = argv[++i]; break;
                    case "--runs":            a.runs            = Integer.parseInt(argv[++i]); break;
                    default:
                        System.err.println("Unknown argument: " + argv[i]);
                }
            }
            return a;
        }
    }
}
