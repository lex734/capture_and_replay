package replay;

import common.BinarySchema;
import common.ReplayBoundaryRegistry;
import common.ReplayBoundaryRegistry.BoundaryMeta;
import common.TraceSemantics;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Distills the rich capture trace into a schedule-oriented boundary artifact.
 *
 * <p>Replay-boundary records carry their raw boundary site in {@code data2}.
 * This is true for:
 * sync/thread-utility boundaries logged through {@code logSync},
 * epoch-advancing atomic events, and replay-boundary volatile writes.
 */
public final class ScheduleDistiller {
    public static final Path DEFAULT_TRACE = Path.of("trace.bin");
    public static final Path DEFAULT_BOUNDARIES = Path.of(ReplayBoundaryRegistry.DEFAULT_FILE);
    public static final Path DEFAULT_OUTPUT = Path.of("schedule-boundaries.tsv");

    private ScheduleDistiller() {}

    public static final class ScheduleEntry {
        public final long seq;
        public final long epoch;
        public final int roleId;
        public final int eventType;
        public final int rawSiteId;
        public final String className;
        public final String methodName;
        public final int occurrence;

        ScheduleEntry(long seq, int roleId, int eventType, int rawSiteId,
                String className, String methodName, int occurrence) {
            this.seq = seq;
            this.epoch = seq >>> 32;
            this.roleId = roleId;
            this.eventType = eventType;
            this.rawSiteId = rawSiteId;
            this.className = className;
            this.methodName = methodName;
            this.occurrence = occurrence;
        }

        public String toTsv() {
            return seq + "\t" + epoch + "\t" + roleId + "\t" + eventType + "\t"
                    + rawSiteId + "\t" + className + "\t" + methodName + "\t"
                    + (occurrence > 0 ? occurrence : "");
        }
    }

    private static final class OccurrenceKey {
        private final int roleId;
        private final int eventType;
        private final String className;
        private final String methodName;

        private OccurrenceKey(int roleId, int eventType, String className, String methodName) {
            this.roleId = roleId;
            this.eventType = eventType;
            this.className = className;
            this.methodName = methodName;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof OccurrenceKey)) return false;
            OccurrenceKey other = (OccurrenceKey) obj;
            return roleId == other.roleId
                    && eventType == other.eventType
                    && Objects.equals(className, other.className)
                    && Objects.equals(methodName, other.methodName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(roleId, eventType, className, methodName);
        }
    }

    public static List<ScheduleEntry> distill(Path tracePath, Path metadataPath) {
        Map<Integer, List<BoundaryMeta>> metadata = ReplayBoundaryRegistry.loadFromFile(metadataPath);
        ArrayList<ScheduleEntry> entries = new ArrayList<>();

        try (FileChannel channel = FileChannel.open(tracePath, StandardOpenOption.READ)) {
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            long slots = channel.size() / BinarySchema.RECORD_SIZE;

            for (long i = 0; i < slots; i++) {
                int pos = (int) (i * BinarySchema.RECORD_SIZE);
                long seq = buffer.getLong(pos);
                if (seq == 0L) continue;

                int packedType = buffer.getInt(pos + 16);
                int eventType = packedType & 0xFF;
                if (!TraceSemantics.isReplayBoundary(packedType)) continue;

                int rawSiteId = extractBoundarySiteId(buffer, pos);
                BoundaryMeta meta = resolveBoundaryMeta(metadata.get(rawSiteId), rawSiteId, eventType);
                if (meta == null) continue;

                int roleId = (int) buffer.getLong(pos + 8);
                entries.add(new ScheduleEntry(seq, roleId, eventType, rawSiteId,
                        meta.className, meta.methodName, 0));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to distill schedule from " + tracePath, e);
        }

        entries.sort(Comparator.comparingLong(e -> e.seq));
        return entries;
    }

    private static int extractBoundarySiteId(MappedByteBuffer buffer, int pos) {
        return buffer.getInt(pos + 32);
    }

    private static BoundaryMeta resolveBoundaryMeta(List<BoundaryMeta> candidates, int rawSiteId, int eventType) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        BoundaryMeta match = null;
        for (BoundaryMeta candidate : candidates) {
            if (candidate.eventType != eventType) {
                continue;
            }
            if (match == null) {
                match = candidate;
                continue;
            }
            if (!Objects.equals(match.className, candidate.className)
                    || !Objects.equals(match.methodName, candidate.methodName)) {
                throw new IllegalStateException(
                        "Ambiguous boundary metadata for rawSiteId=" + rawSiteId
                                + " eventType=" + eventType
                                + ": (" + match.className + "," + match.methodName + ") vs ("
                                + candidate.className + "," + candidate.methodName + ")");
            }
        }
        return match;
    }

    public static void write(Path outputPath, List<ScheduleEntry> entries) {
        List<String> lines = new ArrayList<>(entries.size() + 1);
        lines.add("seq\tepoch\troleId\teventType\trawSiteId\tclassName\tmethodName\toccurrence");
        for (ScheduleEntry entry : entries) {
            lines.add(entry.toTsv());
        }
        try {
            Files.write(outputPath, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write distilled schedule to " + outputPath, e);
        }
    }

    public static void main(String[] args) {
        Path trace = args.length > 0 ? Path.of(args[0]) : DEFAULT_TRACE;
        Path boundaries = args.length > 1 ? Path.of(args[1]) : DEFAULT_BOUNDARIES;
        Path output = args.length > 2 ? Path.of(args[2]) : DEFAULT_OUTPUT;
        write(output, distill(trace, boundaries));
    }
}
