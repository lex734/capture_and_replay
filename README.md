# Capture and Replay Agent

A Java agent that captures and replays the interleaving of synchronization events in a multithreaded program.

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

## Running the Test App

**Capture** — run the program and record the sync event trace to `trace.bin`:
```bash
java -javaagent:capture/target/trace-capture-agent.jar \
     -cp test-app/target/test-app.jar Main
```

**Replay** — re-run the program, enforcing the recorded trace:
```bash
java -javaagent:replay/target/trace-replay-agent.jar \
     -cp test-app/target/test-app.jar Main
```

## Attaching the Agents to Your Own Program

The agents work with any Java program — just substitute your classpath and main class.

**Capture:**
```bash
java -javaagent:capture/target/trace-capture-agent.jar \
     -cp <your-classpath> <YourMainClass> [args...]
```

**Replay** (run after a capture has produced `trace.bin`):
```bash
java -javaagent:replay/target/trace-replay-agent.jar \
     -cp <your-classpath> <YourMainClass> [args...]
```

**Example — attaching to an existing fat jar:**
```bash
# Capture
java -javaagent:capture/target/trace-capture-agent.jar -jar your-app.jar

# Replay
java -javaagent:replay/target/trace-replay-agent.jar -jar your-app.jar
```

The trace is written to / read from `trace.bin` in the working directory.
