package common;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sidecar metadata for replay-relevant boundaries.
 */
public final class ReplayBoundaryRegistry {
    public static final String DEFAULT_FILE = "trace-boundaries.tsv";

    private static final ConcurrentHashMap<Integer, BoundaryMeta> boundaries = new ConcurrentHashMap<>();

    private ReplayBoundaryRegistry() {}

    public static final class BoundaryMeta {
        public final int rawSiteId;
        public final int eventType;
        public final String className;
        public final String methodName;

        private BoundaryMeta(int rawSiteId, int eventType, String className, String methodName) {
            this.rawSiteId = rawSiteId;
            this.eventType = eventType;
            this.className = className;
            this.methodName = methodName;
        }

        public String toTsv() {
            return rawSiteId + "\t" + eventType + "\t" + className + "\t" + methodName;
        }
    }

    public static void register(int rawSiteId, int eventType, String className, String methodName) {
        BoundaryMeta next = new BoundaryMeta(rawSiteId, eventType, className, methodName);
        boundaries.merge(rawSiteId, next, (prev, cur) -> {
            if (prev.eventType != cur.eventType
                    || !prev.className.equals(cur.className)
                    || !prev.methodName.equals(cur.methodName)) {
                throw new IllegalStateException(
                        "Replay boundary site collision for rawSiteId=" + rawSiteId
                                + ": existing=(" + prev.eventType + "," + prev.className + "," + prev.methodName + ")"
                                + " new=(" + cur.eventType + "," + cur.className + "," + cur.methodName + ")");
            }
            return prev;
        });
    }

    public static void writeDefaultFile() {
        writeToFile(Path.of(DEFAULT_FILE));
    }

    public static void writeToFile(Path path) {
        List<BoundaryMeta> ordered = new ArrayList<>(boundaries.values());
        ordered.sort(Comparator.comparingInt(m -> m.rawSiteId));

        List<String> lines = new ArrayList<>(ordered.size() + 1);
        lines.add("rawSiteId\teventType\tclassName\tmethodName");
        for (BoundaryMeta meta : ordered) {
            lines.add(meta.toTsv());
        }

        try {
            Files.write(path, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write replay boundary metadata to " + path, e);
        }
    }

    public static Map<Integer, BoundaryMeta> snapshot() {
        return Map.copyOf(boundaries);
    }

    public static BoundaryMeta get(int rawSiteId) {
        return boundaries.get(rawSiteId);
    }

    public static Map<Integer, BoundaryMeta> loadFromFile(Path path) {
        ConcurrentHashMap<Integer, BoundaryMeta> loaded = new ConcurrentHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (first) {
                    first = false;
                    if (line.startsWith("rawSiteId\t")) continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length != 4) {
                    throw new IllegalArgumentException("Invalid replay boundary metadata line: " + line);
                }
                int rawSiteId = Integer.parseInt(parts[0]);
                int eventType = Integer.parseInt(parts[1]);
                BoundaryMeta next = new BoundaryMeta(rawSiteId, eventType, parts[2], parts[3]);
                loaded.merge(rawSiteId, next, (prev, cur) -> {
                    if (prev.eventType != cur.eventType
                            || !prev.className.equals(cur.className)
                            || !prev.methodName.equals(cur.methodName)) {
                        throw new IllegalStateException(
                                "Replay boundary metadata collision for rawSiteId=" + rawSiteId);
                    }
                    return prev;
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read replay boundary metadata from " + path, e);
        }
        return Map.copyOf(loaded);
    }

    public static void loadIntoRegistry(Path path) {
        boundaries.clear();
        boundaries.putAll(loadFromFile(path));
    }

    public static void reset() {
        boundaries.clear();
    }
}
