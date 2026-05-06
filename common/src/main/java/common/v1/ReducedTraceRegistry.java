package common.v1;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ReducedTraceRegistry {
    private static final Map<String, FieldInteractionDomain> domainsById = new ConcurrentHashMap<>();
    private static final Map<Long, FieldInteractionDomain> fieldDomainBySeq = new ConcurrentHashMap<>();
    private static final Map<Long, List<ReplayConstraint>> constraintsBySuccessor = new ConcurrentHashMap<>();
    private static final Map<Long, Boolean> replayRelevantSeqs = new ConcurrentHashMap<>();

    private ReducedTraceRegistry() {
    }

    public static void reset() {
        domainsById.clear();
        fieldDomainBySeq.clear();
        constraintsBySuccessor.clear();
        replayRelevantSeqs.clear();
    }

    public static void recordDomain(FieldInteractionDomain domain) {
        domainsById.put(domain.domainId(), domain);
    }

    public static void recordFieldEvent(long seq, FieldInteractionDomain domain, boolean replayRelevant) {
        if (domain != null) {
            domainsById.putIfAbsent(domain.domainId(), domain);
            fieldDomainBySeq.put(seq, domain);
        }
        if (replayRelevant) {
            replayRelevantSeqs.put(seq, Boolean.TRUE);
        }
    }

    public static void recordConstraint(ReplayConstraint constraint) {
        constraintsBySuccessor.computeIfAbsent(constraint.successorSeq(), ignored -> Collections.synchronizedList(new ArrayList<>()))
                .add(constraint);
    }

    public static FieldInteractionDomain lookupFieldDomain(long seq) {
        return fieldDomainBySeq.get(seq);
    }

    public static List<ReplayConstraint> lookupConstraints(long successorSeq) {
        List<ReplayConstraint> constraints = constraintsBySuccessor.get(successorSeq);
        return constraints == null ? Collections.emptyList() : new ArrayList<>(constraints);
    }

    public static boolean isReplayRelevant(long seq) {
        return replayRelevantSeqs.containsKey(seq);
    }

    public static void save(String fileName) throws IOException {
        Path path = Path.of(fileName);
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writer.write("# v1-field-reduced");
            writer.newLine();
            for (FieldInteractionDomain domain : domainsById.values()) {
                writer.write("DOMAIN\t");
                writer.write(domain.domainId());
                writer.write('\t');
                writer.write(Integer.toString(domain.ownerSite()));
                writer.write('\t');
                writer.write(Integer.toString(domain.ownerCount()));
                writer.write('\t');
                writer.write(domain.fieldKey().owner().internalName());
                writer.write('\t');
                writer.write(domain.fieldKey().name());
                writer.write('\t');
                writer.write(domain.fieldKey().descriptor());
                writer.newLine();
            }
            for (Map.Entry<Long, FieldInteractionDomain> entry : fieldDomainBySeq.entrySet()) {
                writer.write("EVENT\t");
                writer.write(Long.toString(entry.getKey()));
                writer.write('\t');
                writer.write(entry.getValue().domainId());
                writer.write('\t');
                writer.write(isReplayRelevant(entry.getKey()) ? "1" : "0");
                writer.newLine();
            }
            for (List<ReplayConstraint> constraints : constraintsBySuccessor.values()) {
                for (ReplayConstraint constraint : constraints) {
                    writer.write("CONSTRAINT\t");
                    writer.write(constraint.kind().name());
                    writer.write('\t');
                    writer.write(Long.toString(constraint.predecessorSeq()));
                    writer.write('\t');
                    writer.write(Long.toString(constraint.successorSeq()));
                    writer.write('\t');
                    writer.write(constraint.domainId());
                    writer.newLine();
                }
            }
        }
    }

    public static void load(String fileName) throws IOException {
        reset();
        Path path = Path.of(fileName);
        if (!Files.exists(path)) {
            return;
        }
        Map<Long, String> eventToDomainId = new ConcurrentHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length == 0) {
                    continue;
                }
                switch (parts[0]) {
                    case "DOMAIN":
                        if (parts.length == 7) {
                            FieldInteractionDomain domain = new FieldInteractionDomain(
                                    parts[1],
                                    Integer.parseInt(parts[2]),
                                    Integer.parseInt(parts[3]),
                                    FieldKey.of(parts[4], parts[5], parts[6]));
                            recordDomain(domain);
                        }
                        break;
                    case "EVENT":
                        if (parts.length == 4) {
                            long seq = Long.parseLong(parts[1]);
                            eventToDomainId.put(seq, parts[2]);
                            if ("1".equals(parts[3])) {
                                replayRelevantSeqs.put(seq, Boolean.TRUE);
                            }
                        }
                        break;
                    case "CONSTRAINT":
                        if (parts.length == 5) {
                            recordConstraint(new ReplayConstraint(
                                    ReducedConstraintKind.valueOf(parts[1]),
                                    Long.parseLong(parts[2]),
                                    Long.parseLong(parts[3]),
                                    parts[4]));
                        }
                        break;
                    default:
                        break;
                }
            }
        }
        for (Map.Entry<Long, String> entry : eventToDomainId.entrySet()) {
            FieldInteractionDomain domain = domainsById.get(entry.getValue());
            if (domain != null) {
                fieldDomainBySeq.put(entry.getKey(), domain);
            }
        }
    }
}
