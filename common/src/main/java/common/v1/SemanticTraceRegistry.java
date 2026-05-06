package common.v1;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

public final class SemanticTraceRegistry {
    private static final String VERSION = "v1-field-semantics";
    private static final Object writerLock = new Object();

    private static volatile BufferedWriter fieldWriter;
    private static final Map<Long, SemanticFieldEvent> fieldEventsBySeq = new ConcurrentHashMap<>();

    private SemanticTraceRegistry() {
    }

    public static void reset() {
        closeCapture();
        fieldEventsBySeq.clear();
    }

    public static void closeCapture() {
        synchronized (writerLock) {
            if (fieldWriter != null) {
                try {
                    fieldWriter.close();
                } catch (IOException ignored) {
                }
                fieldWriter = null;
            }
        }
    }

    public static void initCapture(String fileName) throws IOException {
        Path path = Path.of(fileName);
        BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        writer.write("# " + VERSION);
        writer.newLine();
        synchronized (writerLock) {
            if (fieldWriter != null) {
                fieldWriter.close();
            }
            fieldWriter = writer;
        }
    }

    public static void load(String fileName) throws IOException {
        Path path = Path.of(fileName);
        if (!Files.exists(path)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length != 9 || !"FIELD".equals(parts[0])) {
                    continue;
                }
                long seq = Long.parseLong(parts[1]);
                long roleId = Long.parseLong(parts[2]);
                int packedType = Integer.parseInt(parts[3]);
                int ownerSite = Integer.parseInt(parts[4]);
                int ownerCount = Integer.parseInt(parts[5]);
                FieldKey fieldKey = FieldKey.of(parts[6], parts[7], parts[8]);
                fieldEventsBySeq.put(seq, new SemanticFieldEvent(seq, roleId, packedType, ownerSite, ownerCount, fieldKey));
            }
        }
    }

    public static void recordFieldEvent(long seq, long roleId, int packedType, int ownerSite, int ownerCount, FieldKey fieldKey) {
        BufferedWriter writer = fieldWriter;
        if (writer == null || fieldKey == null) {
            return;
        }
        fieldEventsBySeq.put(seq, new SemanticFieldEvent(seq, roleId, packedType, ownerSite, ownerCount, fieldKey));
        synchronized (writerLock) {
            try {
                writer.write("FIELD");
                writer.write('\t');
                writer.write(Long.toString(seq));
                writer.write('\t');
                writer.write(Long.toString(roleId));
                writer.write('\t');
                writer.write(Integer.toString(packedType));
                writer.write('\t');
                writer.write(Integer.toString(ownerSite));
                writer.write('\t');
                writer.write(Integer.toString(ownerCount));
                writer.write('\t');
                writer.write(fieldKey.owner().internalName());
                writer.write('\t');
                writer.write(fieldKey.name());
                writer.write('\t');
                writer.write(fieldKey.descriptor());
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to write semantic field event", e);
            }
        }
    }

    public static SemanticFieldEvent lookupFieldEvent(long seq) {
        return fieldEventsBySeq.get(seq);
    }

    public static Collection<SemanticFieldEvent> snapshotFieldEvents() {
        return new ArrayList<>(fieldEventsBySeq.values());
    }
}
