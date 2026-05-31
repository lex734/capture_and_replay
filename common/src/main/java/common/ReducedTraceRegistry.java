package common;

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
    private static final Map<Long, String> domainIdBySeq = new ConcurrentHashMap<>();
    private static final Map<Long, Long> epochBySeq = new ConcurrentHashMap<>();
    private static final Map<Long, List<ReplayConstraint>> constraintsBySuccessor = new ConcurrentHashMap<>();
    private static final Map<Long, Boolean> replayRelevantSeqs = new ConcurrentHashMap<>();
    private static final List<long[]> reducedEvents = Collections.synchronizedList(new ArrayList<>());

    private ReducedTraceRegistry() {
    }

    public static void reset() {
        domainsById.clear();
        fieldDomainBySeq.clear();
        domainIdBySeq.clear();
        epochBySeq.clear();
        constraintsBySuccessor.clear();
        replayRelevantSeqs.clear();
        reducedEvents.clear();
    }

    public static void recordDomain(FieldInteractionDomain domain) {
        domainsById.put(domain.domainId(), domain);
    }

    public static void recordFieldEvent(long seq, FieldInteractionDomain domain, boolean replayRelevant) {
        if (domain != null) {
            domainsById.putIfAbsent(domain.domainId(), domain);
            fieldDomainBySeq.put(seq, domain);
            domainIdBySeq.put(seq, domain.domainId());
        }
        if (replayRelevant) {
            replayRelevantSeqs.put(seq, Boolean.TRUE);
        }
    }

    public static void recordEventDomain(long seq, String domainId, boolean replayRelevant) {
        if (domainId != null && !domainId.isEmpty()) {
            domainIdBySeq.put(seq, domainId);
        }
        if (replayRelevant) {
            replayRelevantSeqs.put(seq, Boolean.TRUE);
        }
    }

    public static void recordEventEpoch(long seq, long epoch) {
        epochBySeq.put(seq, epoch);
    }

    public static void recordConstraint(ReplayConstraint constraint) {
        constraintsBySuccessor
                .computeIfAbsent(constraint.successorSeq(), ignored -> Collections.synchronizedList(new ArrayList<>()))
                .add(constraint);
    }

    public static void recordReducedEvent(long replayEventId, long[] event) {
        reducedEvents.add(new long[] {
                replayEventId, event[1], event[2], event[3], event[4], event[5], event[6]
        });
    }

    public static FieldInteractionDomain lookupFieldDomain(long seq) {
        return fieldDomainBySeq.get(seq);
    }

    public static String lookupDomainId(long seq) {
        return domainIdBySeq.get(seq);
    }

    public static long lookupEpoch(long seq) {
        Long epoch = epochBySeq.get(seq);
        return epoch == null ? Long.MIN_VALUE : epoch.longValue();
    }

    public static List<ReplayConstraint> lookupConstraints(long successorSeq) {
        List<ReplayConstraint> constraints = constraintsBySuccessor.get(successorSeq);
        return constraints == null ? Collections.emptyList() : new ArrayList<>(constraints);
    }

    public static boolean isReplayRelevant(long seq) {
        return replayRelevantSeqs.containsKey(seq);
    }

    public static List<long[]> snapshotReducedEvents() {
        return new ArrayList<>(reducedEvents);
    }

    public static void save(String fileName) throws IOException {
        Path path = Path.of(fileName);
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writer.write("# v1-reduced");
            writer.newLine();
            for (FieldInteractionDomain domain : domainsById.values()) {
                writer.write("FIELD_DOMAIN\t");
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
            for (Map.Entry<Long, String> entry : domainIdBySeq.entrySet()) {
                writer.write("EVENT\t");
                    writer.write(Long.toString(entry.getKey()));
                    writer.write('\t');
                    writer.write(entry.getValue());
                    writer.write('\t');
                    writer.write(isReplayRelevant(entry.getKey()) ? "1" : "0");
                    writer.write('\t');
                    writer.write(Long.toString(lookupEpoch(entry.getKey())));
                    writer.newLine();
                }
            for (long[] event : reducedEvents) {
                writer.write("RAW\t");
                writer.write(Long.toString(event[0]));
                writer.write('\t');
                writer.write(Long.toString(event[2]));
                writer.write('\t');
                writer.write(Long.toString(event[3]));
                writer.write('\t');
                writer.write(Long.toString(event[4]));
                writer.write('\t');
                writer.write(Long.toString(event[5]));
                writer.write('\t');
                writer.write(Long.toString(event[6]));
                writer.write('\t');
                writer.write(Long.toString(event[1]));
                writer.newLine();
            }
            for (long[] event : reducedEvents) {
                long replaySeq = event[0];
                SemanticObjectEvent objectEvent = SemanticTraceRegistry.lookupObjectEvent(replaySeq);
                if (objectEvent == null) {
                    continue;
                }
                writer.write("SEMANTIC\t");
                writer.write(Long.toString(replaySeq));
                writer.write('\t');
                writer.write(objectEvent.kind().name());
                writer.write('\t');
                writer.write(Long.toString(objectEvent.roleId()));
                writer.write('\t');
                writer.write(Integer.toString(objectEvent.packedType()));
                writer.write('\t');
                writer.write(Integer.toString(objectEvent.ownerSite()));
                writer.write('\t');
                writer.write(Integer.toString(objectEvent.ownerCount()));
                writer.write('\t');
                writer.write(objectEvent.ownerTypeName());
                writer.write('\t');
                writer.write(Integer.toString(objectEvent.index()));
                writer.write('\t');
                writer.write(Integer.toString(objectEvent.sourceSiteId()));
                writer.write('\t');
                writer.write(Integer.toString(objectEvent.targetRoleId()));
                writer.write('\t');
                if (objectEvent.fieldKey() != null) {
                    writer.write(objectEvent.fieldKey().owner().internalName());
                    writer.write('\t');
                    writer.write(objectEvent.fieldKey().name());
                    writer.write('\t');
                    writer.write(objectEvent.fieldKey().descriptor());
                } else {
                    writer.write('\t');
                    writer.write('\t');
                }
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
                    case "FIELD_DOMAIN":
                    case "DOMAIN":
                        if (parts.length == 7) {
                            recordDomain(new FieldInteractionDomain(
                                    parts[1],
                                    Integer.parseInt(parts[2]),
                                    Integer.parseInt(parts[3]),
                                    FieldKey.of(parts[4], parts[5], parts[6])));
                        }
                        break;
                    case "EVENT":
                        if (parts.length == 5) {
                            long seq = Long.parseLong(parts[1]);
                            domainIdBySeq.put(seq, parts[2]);
                            if ("1".equals(parts[3])) {
                                replayRelevantSeqs.put(seq, Boolean.TRUE);
                            }
                            epochBySeq.put(seq, Long.parseLong(parts[4]));
                        } else if (parts.length == 4) {
                            long seq = Long.parseLong(parts[1]);
                            domainIdBySeq.put(seq, parts[2]);
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
                    case "RAW":
                        if (parts.length == 8) {
                            long replayId = Long.parseLong(parts[1]);
                            recordReducedEvent(replayId, new long[] {
                                    replayId,
                                    Long.parseLong(parts[7]),
                                    Long.parseLong(parts[2]),
                                    Long.parseLong(parts[3]),
                                    Long.parseLong(parts[4]),
                                    Long.parseLong(parts[5]),
                                    Long.parseLong(parts[6])
                            });
                        } else if (parts.length == 9) {
                            long replayId = Long.parseLong(parts[1]);
                            recordReducedEvent(replayId, new long[] {
                                    replayId,
                                    Long.parseLong(parts[8]),
                                    Long.parseLong(parts[3]),
                                    Long.parseLong(parts[4]),
                                    Long.parseLong(parts[5]),
                                    Long.parseLong(parts[6]),
                                    Long.parseLong(parts[7])
                            });
                        }
                        break;
                    case "SEMANTIC":
                        if (parts.length == 14) {
                            long replaySeq = Long.parseLong(parts[1]);
                            SemanticObjectEvent.Kind kind = SemanticObjectEvent.Kind.valueOf(parts[2]);
                            long roleId = Long.parseLong(parts[3]);
                            int packedType = Integer.parseInt(parts[4]);
                            int ownerSite = Integer.parseInt(parts[5]);
                            int ownerCount = Integer.parseInt(parts[6]);
                            String ownerTypeName = parts[7];
                            int index = Integer.parseInt(parts[8]);
                            int sourceSiteId = Integer.parseInt(parts[9]);
                            int targetRoleId = Integer.parseInt(parts[10]);
                            String fieldOwner = parts[11];
                            String fieldName = parts[12];
                            String fieldDesc = parts[13];
                            SemanticObjectEvent event;
                            switch (kind) {
                                case FIELD:
                                    event = SemanticObjectEvent.field(replaySeq, roleId, packedType, ownerSite, ownerCount,
                                            FieldKey.of(fieldOwner, fieldName, fieldDesc));
                                    break;
                                case ARRAY:
                                    event = SemanticObjectEvent.array(replaySeq, roleId, packedType, ownerSite, ownerCount,
                                            ownerTypeName, index);
                                    break;
                                case ATOMIC:
                                    event = SemanticObjectEvent.atomic(replaySeq, roleId, packedType, ownerSite, ownerCount,
                                            ownerTypeName, index);
                                    break;
                                case THREAD:
                                    event = SemanticObjectEvent.thread(replaySeq, roleId, packedType, ownerSite, ownerCount,
                                            ownerTypeName, sourceSiteId, targetRoleId);
                                    break;
                                case CLASS_INIT:
                                    event = SemanticObjectEvent.classInit(replaySeq, roleId, packedType, sourceSiteId);
                                    break;
                                case EXCEPTION:
                                    event = SemanticObjectEvent.exception(replaySeq, roleId, packedType, ownerSite, ownerCount,
                                            ownerTypeName, sourceSiteId);
                                    break;
                                case NONDET:
                                    event = SemanticObjectEvent.nondeterministic(replaySeq, roleId, packedType, sourceSiteId);
                                    break;
                                case SYNC:
                                default:
                                    event = SemanticObjectEvent.sync(replaySeq, roleId, packedType, ownerSite, ownerCount,
                                            ownerTypeName, sourceSiteId);
                                    break;
                            }
                            SemanticTraceRegistry.recordReplayObjectEvent(replaySeq, event);
                        }
                        break;
                    case "SEMANTIC_FIELD":
                        if (parts.length == 9) {
                            long replaySeq = Long.parseLong(parts[1]);
                            SemanticTraceRegistry.recordReplayObjectEvent(replaySeq, SemanticObjectEvent.field(
                                    replaySeq,
                                    Long.parseLong(parts[2]),
                                    Integer.parseInt(parts[3]),
                                    Integer.parseInt(parts[4]),
                                    Integer.parseInt(parts[5]),
                                    FieldKey.of(parts[6], parts[7], parts[8])));
                        }
                        break;
                    case "SEMANTIC_ARRAY":
                    case "SEMANTIC_ATOMIC":
                        if (parts.length == 8) {
                            long replaySeq = Long.parseLong(parts[1]);
                            SemanticTraceRegistry.recordReplayObjectEvent(replaySeq,
                                    "SEMANTIC_ARRAY".equals(parts[0])
                                            ? SemanticObjectEvent.array(replaySeq, Long.parseLong(parts[2]),
                                                    Integer.parseInt(parts[3]), Integer.parseInt(parts[4]),
                                                    Integer.parseInt(parts[5]), parts[6], Integer.parseInt(parts[7]))
                                            : SemanticObjectEvent.atomic(replaySeq, Long.parseLong(parts[2]),
                                                    Integer.parseInt(parts[3]), Integer.parseInt(parts[4]),
                                                    Integer.parseInt(parts[5]), parts[6], Integer.parseInt(parts[7])));
                        }
                        break;
                    default:
                        break;
                }
            }
        }
        for (Map.Entry<Long, String> entry : domainIdBySeq.entrySet()) {
            FieldInteractionDomain domain = domainsById.get(entry.getValue());
            if (domain != null) {
                fieldDomainBySeq.put(entry.getKey(), domain);
            }
        }
    }
}
