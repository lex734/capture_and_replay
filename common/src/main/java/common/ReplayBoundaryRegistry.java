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

    private static final ConcurrentHashMap<Integer, ConcurrentHashMap<String, BoundaryMeta>> boundaries =
            new ConcurrentHashMap<>();

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

        private String descriptorKey() {
            return eventType + "\t" + className + "\t" + methodName;
        }
    }

    public static void register(int rawSiteId, int eventType, String className, String methodName) {
        BoundaryMeta next = new BoundaryMeta(rawSiteId, eventType, className, methodName);
        boundaries.computeIfAbsent(rawSiteId, ignored -> new ConcurrentHashMap<>())
                .putIfAbsent(next.descriptorKey(), next);
    }

    public static void writeDefaultFile() {
        writeToFile(Path.of(DEFAULT_FILE));
    }

    public static void writeToFile(Path path) {
        List<BoundaryMeta> ordered = flatten(boundaries);
        ordered.sort(Comparator
                .comparingInt((BoundaryMeta m) -> m.rawSiteId)
                .thenComparingInt(m -> m.eventType)
                .thenComparing(m -> m.className)
                .thenComparing(m -> m.methodName));

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

    public static Map<Integer, List<BoundaryMeta>> snapshot() {
        return materialize(boundaries);
    }

    public static Map<Integer, List<BoundaryMeta>> loadFromFile(Path path) {
        ConcurrentHashMap<Integer, ConcurrentHashMap<String, BoundaryMeta>> loaded = new ConcurrentHashMap<>();
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
                loaded.computeIfAbsent(rawSiteId, ignored -> new ConcurrentHashMap<>())
                        .putIfAbsent(next.descriptorKey(), next);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read replay boundary metadata from " + path, e);
        }
        return materialize(loaded);
    }

    public static void reset() {
        boundaries.clear();
    }

    private static List<BoundaryMeta> flatten(
            ConcurrentHashMap<Integer, ConcurrentHashMap<String, BoundaryMeta>> source) {
        ArrayList<BoundaryMeta> flattened = new ArrayList<>();
        for (ConcurrentHashMap<String, BoundaryMeta> byDescriptor : source.values()) {
            flattened.addAll(byDescriptor.values());
        }
        return flattened;
    }

    private static Map<Integer, List<BoundaryMeta>> materialize(
            ConcurrentHashMap<Integer, ConcurrentHashMap<String, BoundaryMeta>> source) {
        ConcurrentHashMap<Integer, List<BoundaryMeta>> result = new ConcurrentHashMap<>();
        for (Map.Entry<Integer, ConcurrentHashMap<String, BoundaryMeta>> entry : source.entrySet()) {
            ArrayList<BoundaryMeta> metas = new ArrayList<>(entry.getValue().values());
            metas.sort(Comparator
                    .comparingInt((BoundaryMeta m) -> m.eventType)
                    .thenComparing(m -> m.className)
                    .thenComparing(m -> m.methodName));
            result.put(entry.getKey(), List.copyOf(metas));
        }
        return Map.copyOf(result);
    }
}
