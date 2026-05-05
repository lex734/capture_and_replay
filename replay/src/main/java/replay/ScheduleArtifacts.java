package replay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class ScheduleArtifacts {
    private ScheduleArtifacts() {}

    static List<ScheduleDistiller.ScheduleEntry> loadSchedule(Path path) {
        List<ScheduleDistiller.ScheduleEntry> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (first) {
                    first = false;
                    if (line.startsWith("seq\t")) continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length != 7) {
                    throw new IllegalArgumentException("Invalid schedule artifact line: " + line);
                }
                long seq = Long.parseLong(parts[0]);
                int roleId = Integer.parseInt(parts[2]);
                int eventType = Integer.parseInt(parts[3]);
                int rawSiteId = Integer.parseInt(parts[4]);
                entries.add(new ScheduleDistiller.ScheduleEntry(
                        seq, roleId, eventType, rawSiteId, parts[5], parts[6]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load schedule artifact from " + path, e);
        }
        return entries;
    }
}
