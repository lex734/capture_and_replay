package regression.core;

public final class RunResult {
    public final String stdout;
    public final String stderr;
    public final int exitCode;
    public final boolean timedOut;

    public RunResult(String stdout, String stderr, int exitCode, boolean timedOut) {
        this.stdout   = stdout;
        this.stderr   = stderr;
        this.exitCode = exitCode;
        this.timedOut = timedOut;
    }

    public boolean isCleanExit() {
        return !timedOut && exitCode == 0;
    }
}
