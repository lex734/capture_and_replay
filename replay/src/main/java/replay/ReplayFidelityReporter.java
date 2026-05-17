package replay;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class ReplayFidelityReporter {
    static final String OUTPUT_PROPERTY = "tool.fidelity.output";
    static final String STATIC_STATUS_PROPERTY = "tool.static.analysis.status";
    static final String STATIC_REASON_PROPERTY = "tool.static.analysis.reason";

    private static final Object hookLock = new Object();
    private static volatile boolean hookInstalled = false;
    private static final AtomicBoolean outputHooksInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean hadBug = new AtomicBoolean(false);
    private static final AtomicReference<String> outcomeSignal = new AtomicReference<>("");

    private ReplayFidelityReporter() {}

    static void installShutdownHookIfRequested() {
        String output = System.getProperty(OUTPUT_PROPERTY);
        if (output == null || output.isBlank()) {
            return;
        }
        if (hookInstalled) {
            return;
        }
        synchronized (hookLock) {
            if (hookInstalled) {
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(
                    ReplayFidelityReporter::writeReportSafely,
                    "replay-fidelity-report"));
            hookInstalled = true;
        }
    }

    static void installOutcomeHooksIfRequested() {
        String output = System.getProperty(OUTPUT_PROPERTY);
        if (output == null || output.isBlank()) {
            return;
        }
        if (!outputHooksInstalled.compareAndSet(false, true)) {
            return;
        }

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(wrapPrintStream(originalOut));
        System.setErr(wrapPrintStream(originalErr));
    }

    static void recordThrowable(Throwable throwable) {
        if (throwable == null) {
            return;
        }
        if (throwable instanceof AssertionError) {
            recordBugSignal(throwable.toString());
            return;
        }
        recordBugSignalIfPresent(throwable.toString());
    }

    private static PrintStream wrapPrintStream(PrintStream delegate) {
        return new PrintStream(new DetectingOutputStream(delegate), true);
    }

    private static void recordBugSignalIfPresent(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (text.contains("AssertionError")
                || text.contains("Bug Found!")
                || text.contains("Bug found!")
                || text.contains("Deadlock detected")
                || text.contains("RuntimeException: deadlock")) {
            recordBugSignal(text);
        }
    }

    private static void recordBugSignal(String text) {
        hadBug.set(true);
        if (text != null && !text.isBlank()) {
            outcomeSignal.compareAndSet("", text);
        }
    }

    private static void writeReportSafely() {
        try {
            writeReport();
        } catch (Throwable t) {
            System.err.println("[ReplayAgent] Failed to write fidelity metrics: " + t);
        }
    }

    private static void writeReport() throws IOException {
        String output = System.getProperty(OUTPUT_PROPERTY);
        if (output == null || output.isBlank()) {
            return;
        }

        Properties props = new Properties();
        String staticStatus = System.getProperty(STATIC_STATUS_PROPERTY, "UNKNOWN");
        String staticReason = System.getProperty(STATIC_REASON_PROPERTY, "");
        long eventsMatched = ReplayCoordinator.getEventsMatched();
        long eventsTotal = ReplayCoordinator.getTotalBoundaryEvents();
        boolean structuralDivergence = ReplayCoordinator.hasDiverged()
                || !"APPLICABLE".equals(staticStatus);

        props.setProperty("structural_divergence", Boolean.toString(structuralDivergence));
        props.setProperty("events_matched", Long.toString(eventsMatched));
        props.setProperty("events_total", Long.toString(eventsTotal));
        props.setProperty("valued_events", "0");
        props.setProperty("natural_agreements", "0");
        props.setProperty("injections", "0");
        props.setProperty("no_inject_disagreements", "0");
        props.setProperty("had_bug", Boolean.toString(hadBug.get()));
        props.setProperty("static_analysis_status", staticStatus);
        if (!staticReason.isBlank()) {
            props.setProperty("static_analysis_reason", staticReason);
        }

        String firstOutcomeSignal = outcomeSignal.get();
        if (firstOutcomeSignal != null && !firstOutcomeSignal.isBlank()) {
            props.setProperty("outcome_signal", firstOutcomeSignal);
        }

        String divergenceInfo = ReplayCoordinator.getFirstDivergenceInfo();
        if (divergenceInfo != null && !divergenceInfo.isBlank()) {
            props.setProperty("divergence_info", divergenceInfo);
        }

        Path outputPath = Path.of(output);
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(outputPath)) {
            props.store(writer, "Replay fidelity metrics");
        }
    }

    private static final class DetectingOutputStream extends OutputStream {
        private final PrintStream delegate;
        private final StringBuilder lineBuffer = new StringBuilder();

        private DetectingOutputStream(PrintStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            appendAndScan((char) (b & 0xFF));
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            for (int i = off; i < off + len; i++) {
                appendAndScan((char) (b[i] & 0xFF));
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            flush();
        }

        private void appendAndScan(char ch) {
            if (ch == '\r') {
                return;
            }
            if (ch == '\n') {
                recordBugSignalIfPresent(lineBuffer.toString());
                lineBuffer.setLength(0);
                return;
            }
            lineBuffer.append(ch);
            if (lineBuffer.length() > 512) {
                lineBuffer.delete(0, lineBuffer.length() - 512);
            }
            recordBugSignalIfPresent(lineBuffer.toString());
        }
    }
}
