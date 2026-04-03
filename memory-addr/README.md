# memory-addr

Prints the heap addresses of a `LinkedList` and each of its internal `Node` objects using [JOL](https://openjdk.org/projects/code-tools/jol/).

## Run the demo

```bash
cd memory-addr
mvn compile exec:java
```

The `.mvn/jvm.config` file already contains `--add-opens java.base/java.util=ALL-UNNAMED`, which is required for the reflection-based node walking to work on Java 9+.

## Play with the JOL CLI

`jol-cli.jar` is checked in to this directory. Run it with:

```bash
java -jar jol-cli.jar <mode> [args]
```

### Available modes

| Mode | What it does |
|------|-------------|
| `internals` | Field layout, padding, and object header for a class |
| `externals` | All objects reachable from an instance (object graph) |
| `estimates` | Simulated layout under different VM/GC modes |
| `footprint` | Total memory footprint of an object graph |
| `shapes` | Object shapes present in JAR files or heap dumps |

### Examples

```bash
# Show internal layout of LinkedList (field offsets, header size, padding)
java -jar jol-cli.jar internals java.util.LinkedList

# Show internal layout of a specific class from your own jar
java -jar jol-cli.jar internals -cp target/memory-addr-1.0-SNAPSHOT.jar java.util.LinkedList

# Estimate layout under different GC/VM configurations
java -jar jol-cli.jar estimates java.util.LinkedList

# Show footprint of String
java -jar jol-cli.jar footprint java.lang.String
```
