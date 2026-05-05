# Capture and Replay Agent

A Java agent that captures a synchronization trace from a multithreaded Java
program and replays a distilled boundary schedule on a later run.

## Build

Requires Maven and JDK 11+.

```bash
mvn package
```

Artifacts produced:
- `capture/target/trace-capture-agent.jar` — capture agent (fat jar)
- `replay/target/trace-replay-agent.jar` — replay agent (fat jar)
- `test-app/target/test-app.jar` — bundled test application

To skip rebuilding unchanged modules:
```bash
mvn package -pl capture,replay --am
```

To compile a single Java source file directly with `javac`:

```bash
javac -cp test-app/target/test-app.jar -d /tmp/classes path/to/YourClass.java
```

For example, to compile one test class from `test-app` into a throwaway output directory:

```bash
mkdir -p /tmp/classes
javac -cp test-app/target/test-app.jar \
      -d /tmp/classes \
      test-app/src/main/java/correctness/AtomicCounterTest.java
```

You can then run that class with:

```bash
java -cp /tmp/classes:test-app/target/test-app.jar correctness.AtomicCounterTest
```

## Running the Test App

**Capture** — run the program and record:
- `trace.bin` — raw captured trace
- `trace-boundaries.tsv` — replay-boundary metadata sidecar

```bash
java -javaagent:capture/target/trace-capture-agent.jar \
     -cp test-app/target/test-app.jar Main
```

**Replay** — replay now does four things automatically before the target
program starts:
- distills `trace.bin` + `trace-boundaries.tsv` into `schedule-boundaries.tsv`
- runs mandatory static applicability analysis against the target program
- aborts early if the run is statically inapplicable
- otherwise enables boundary-coordinated schedule replay

```bash
java -javaagent:replay/target/trace-replay-agent.jar \
     -cp test-app/target/test-app.jar Main
```

The replay agent expects either:
- a single application jar on the classpath, or
- a single classes directory on the classpath

If the target cannot be inferred from `java.class.path`, set it explicitly:

```bash
java -Dtool.static.analysis.path=/path/to/app.jar \
     -javaagent:replay/target/trace-replay-agent.jar \
     -cp test-app/target/test-app.jar Main
```

## Attaching the Agents to Your Own Program

The agents work with any Java program — just substitute your classpath and main class.

**Capture:**
```bash
java -javaagent:capture/target/trace-capture-agent.jar \
     -cp <your-classpath> <YourMainClass> [args...]
```

**Replay** (run after capture has produced `trace.bin` and `trace-boundaries.tsv`):
```bash
java -javaagent:replay/target/trace-replay-agent.jar \
     -cp <your-classpath> <YourMainClass> [args...]
```

If replay cannot determine the target jar/classes root from the application
classpath, pass it explicitly:

```bash
java -Dtool.static.analysis.path=<path-to-app-jar-or-classes-dir> \
     -javaagent:replay/target/trace-replay-agent.jar \
     -cp <your-classpath> <YourMainClass> [args...]
```

**Example — attaching to an existing fat jar:**
```bash
# Capture
java -javaagent:capture/target/trace-capture-agent.jar -jar your-app.jar

# Replay
java -javaagent:replay/target/trace-replay-agent.jar -jar your-app.jar
```

## Generated Files

These files are written in the working directory:
- `trace.bin` — raw captured trace
- `trace-boundaries.tsv` — raw boundary metadata emitted during capture
- `schedule-boundaries.tsv` — distilled replay schedule emitted during replay startup

## Optional Offline Tools

Distill the schedule manually:

```bash
java -cp replay/target/trace-replay-agent.jar replay.ScheduleDistiller
```

Run the mandatory static analyzer manually:

```bash
java -cp replay/target/trace-replay-agent.jar replay.ScheduleStaticAnalyzer \
     schedule-boundaries.tsv <app.jar-or-classes-dir>
```
