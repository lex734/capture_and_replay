package common;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SemanticTraceRegistry {
    private static final List<SemanticObjectEvent> capturedObjectEvents =
            Collections.synchronizedList(new ArrayList<>());
    private static final Map<Long, SemanticObjectEvent> replayObjectEventsBySeq = new ConcurrentHashMap<>();
    private SemanticTraceRegistry() {
    }

    public static void reset() {
        resetCaptureState();
        resetReplayState();
    }

    public static void resetCaptureState() {
        capturedObjectEvents.clear();
    }

    public static void resetReplayState() {
        replayObjectEventsBySeq.clear();
    }

    public static void recordFieldEvent(long seq, long roleId, int packedType, int ownerSite, int ownerCount, FieldKey fieldKey) {
        if (fieldKey == null) {
            return;
        }
        capturedObjectEvents.add(SemanticObjectEvent.field(
                seq, roleId, packedType, ownerSite, ownerCount, fieldKey));
    }

    public static void recordArrayEvent(long seq, long roleId, int packedType,
            int ownerSite, int ownerCount, String ownerTypeName, int index) {
        SemanticObjectEvent event = SemanticObjectEvent.array(
                seq, roleId, packedType, ownerSite, ownerCount, ownerTypeName, index);
        capturedObjectEvents.add(event);
    }

    public static void recordAtomicEvent(long seq, long roleId, int packedType,
            int ownerSite, int ownerCount, String ownerTypeName, int index) {
        SemanticObjectEvent event = SemanticObjectEvent.atomic(
                seq, roleId, packedType, ownerSite, ownerCount, ownerTypeName, index);
        capturedObjectEvents.add(event);
    }

    public static void recordSyncEvent(long seq, long roleId, int packedType,
            int ownerSite, int ownerCount, String ownerTypeName, int sourceSiteId) {
        capturedObjectEvents.add(SemanticObjectEvent.sync(
                seq, roleId, packedType, ownerSite, ownerCount, ownerTypeName, sourceSiteId));
    }

    public static void recordThreadEvent(long seq, long roleId, int packedType,
            int ownerSite, int ownerCount, String ownerTypeName, int sourceSiteId, int targetRoleId) {
        capturedObjectEvents.add(SemanticObjectEvent.thread(
                seq, roleId, packedType, ownerSite, ownerCount, ownerTypeName, sourceSiteId, targetRoleId));
    }

    public static void recordClassInitEvent(long seq, long roleId, int packedType, int sourceSiteId) {
        capturedObjectEvents.add(SemanticObjectEvent.classInit(seq, roleId, packedType, sourceSiteId));
    }

    public static void recordExceptionEvent(long seq, long roleId, int packedType,
            int ownerSite, int ownerCount, String ownerTypeName, int sourceSiteId) {
        capturedObjectEvents.add(SemanticObjectEvent.exception(
                seq, roleId, packedType, ownerSite, ownerCount, ownerTypeName, sourceSiteId));
    }

    public static void recordNondeterministicEvent(long seq, long roleId, int packedType, int sourceSiteId) {
        capturedObjectEvents.add(SemanticObjectEvent.nondeterministic(seq, roleId, packedType, sourceSiteId));
    }

    public static void recordReplayObjectEvent(long replaySeq, SemanticObjectEvent event) {
        if (event == null) {
            return;
        }
        replayObjectEventsBySeq.put(replaySeq, event);
    }

    public static SemanticObjectEvent lookupObjectEvent(long replaySeq) {
        return replayObjectEventsBySeq.get(replaySeq);
    }

    public static Collection<SemanticObjectEvent> snapshotCapturedObjectEvents() {
        synchronized (capturedObjectEvents) {
            return new ArrayList<>(capturedObjectEvents);
        }
    }

    public static void saveCaptured(String fileName) throws IOException {
        Path path = Path.of(fileName);
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("# v1-semantic-capture");
            writer.newLine();
            synchronized (capturedObjectEvents) {
                for (SemanticObjectEvent event : capturedObjectEvents) {
                    writeCapturedEvent(writer, event);
                }
            }
        }
    }

    public static Collection<SemanticObjectEvent> loadCaptured(String fileName) throws IOException {
        Path path = Path.of(fileName);
        if (!Files.exists(path)) {
            return List.of();
        }
        List<SemanticObjectEvent> events = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 11 || !"CAPTURE".equals(parts[0])) {
                    continue;
                }
                SemanticObjectEvent.Kind kind = SemanticObjectEvent.Kind.valueOf(parts[1]);
                long seq = Long.parseLong(parts[2]);
                long roleId = Long.parseLong(parts[3]);
                int packedType = Integer.parseInt(parts[4]);
                int ownerSite = Integer.parseInt(parts[5]);
                int ownerCount = Integer.parseInt(parts[6]);
                String ownerTypeName = parts[7];
                int index = Integer.parseInt(parts[8]);
                int sourceSiteId = Integer.parseInt(parts[9]);
                int targetRoleId = Integer.parseInt(parts[10]);
                FieldKey fieldKey = null;
                if (parts.length >= 14 && !parts[11].isEmpty()) {
                    fieldKey = FieldKey.of(parts[11], parts[12], parts[13]);
                }
                events.add(rebuildCapturedEvent(kind, seq, roleId, packedType, ownerSite, ownerCount,
                        ownerTypeName, fieldKey, index, sourceSiteId, targetRoleId));
            }
        }
        return events;
    }

    public static String semanticShapeKey(long replaySeq, String domainId) {
        SemanticObjectEvent event = lookupObjectEvent(replaySeq);
        return event == null ? "" : event.semanticObjectShape(domainId);
    }

    private static void writeCapturedEvent(BufferedWriter writer, SemanticObjectEvent event) throws IOException {
        writer.write("CAPTURE\t");
        writer.write(event.kind().name());
        writer.write('\t');
        writer.write(Long.toString(event.seq()));
        writer.write('\t');
        writer.write(Long.toString(event.roleId()));
        writer.write('\t');
        writer.write(Integer.toString(event.packedType()));
        writer.write('\t');
        writer.write(Integer.toString(event.ownerSite()));
        writer.write('\t');
        writer.write(Integer.toString(event.ownerCount()));
        writer.write('\t');
        writer.write(event.ownerTypeName());
        writer.write('\t');
        writer.write(Integer.toString(event.index()));
        writer.write('\t');
        writer.write(Integer.toString(event.sourceSiteId()));
        writer.write('\t');
        writer.write(Integer.toString(event.targetRoleId()));
        writer.write('\t');
        if (event.fieldKey() != null) {
            writer.write(event.fieldKey().owner().internalName());
            writer.write('\t');
            writer.write(event.fieldKey().name());
            writer.write('\t');
            writer.write(event.fieldKey().descriptor());
        } else {
            writer.write('\t');
            writer.write('\t');
        }
        writer.newLine();
    }

    private static SemanticObjectEvent rebuildCapturedEvent(SemanticObjectEvent.Kind kind, long seq, long roleId,
            int packedType, int ownerSite, int ownerCount, String ownerTypeName, FieldKey fieldKey, int index,
            int sourceSiteId, int targetRoleId) {
        switch (kind) {
            case FIELD:
                return SemanticObjectEvent.field(seq, roleId, packedType, ownerSite, ownerCount, fieldKey);
            case ARRAY:
                return SemanticObjectEvent.array(seq, roleId, packedType, ownerSite, ownerCount, ownerTypeName, index);
            case ATOMIC:
                return SemanticObjectEvent.atomic(seq, roleId, packedType, ownerSite, ownerCount, ownerTypeName, index);
            case THREAD:
                return SemanticObjectEvent.thread(seq, roleId, packedType, ownerSite, ownerCount, ownerTypeName,
                        sourceSiteId, targetRoleId);
            case CLASS_INIT:
                return SemanticObjectEvent.classInit(seq, roleId, packedType, sourceSiteId);
            case EXCEPTION:
                return SemanticObjectEvent.exception(seq, roleId, packedType, ownerSite, ownerCount, ownerTypeName,
                        sourceSiteId);
            case NONDET:
                return SemanticObjectEvent.nondeterministic(seq, roleId, packedType, sourceSiteId);
            case SYNC:
            default:
                return SemanticObjectEvent.sync(seq, roleId, packedType, ownerSite, ownerCount, ownerTypeName,
                        sourceSiteId);
        }
    }
}
