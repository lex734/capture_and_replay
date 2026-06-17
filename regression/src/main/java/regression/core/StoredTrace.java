package regression.core;

import regression.annotations.OutcomeExpectation;
import java.nio.file.Path;

public final class StoredTrace {
    public final String sha256;
    public final Path dir;
    public final OutcomeExpectation expect;
    public final String observedOutput;
    public final String desc;

    public StoredTrace(String sha256, Path dir, OutcomeExpectation expect,
                       String observedOutput, String desc) {
        this.sha256         = sha256;
        this.dir            = dir;
        this.expect         = expect;
        this.observedOutput = observedOutput;
        this.desc           = desc;
    }

    public Path reducedTrace() {
        return dir.resolve("trace-reduced.tsv");
    }
}
