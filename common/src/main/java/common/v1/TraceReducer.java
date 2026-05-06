package common.v1;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TraceReducer {
    private TraceReducer() {
    }

    public static void reduceFieldInteractionsToFile(String fileName) throws IOException {
        ReducedTraceRegistry.reset();

        List<SemanticFieldEvent> events = new ArrayList<>(SemanticTraceRegistry.snapshotFieldEvents());
        events.sort(Comparator.comparingLong(SemanticFieldEvent::seq));

        Map<Long, SemanticFieldEvent> lastByRole = new HashMap<>();
        Map<String, SemanticFieldEvent> lastByDomain = new HashMap<>();
        Map<String, FieldInteractionDomain> domains = new HashMap<>();

        for (SemanticFieldEvent event : events) {
            String domainId = buildDomainId(event.ownerSite(), event.ownerCount(), event.fieldKey());
            FieldInteractionDomain domain = domains.get(domainId);
            if (domain == null) {
                domain = new FieldInteractionDomain(domainId, event.ownerSite(), event.ownerCount(), event.fieldKey());
                domains.put(domainId, domain);
            }
            ReducedTraceRegistry.recordFieldEvent(event.seq(), domain, true);

            SemanticFieldEvent previousRoleEvent = lastByRole.put(event.roleId(), event);
            if (previousRoleEvent != null) {
                ReducedTraceRegistry.recordConstraint(new ReplayConstraint(
                        ReducedConstraintKind.THREAD_ORDER,
                        previousRoleEvent.seq(),
                        event.seq(),
                        domain.domainId()));
            }

            SemanticFieldEvent previousDomainEvent = lastByDomain.put(domain.domainId(), event);
            if (previousDomainEvent != null) {
                ReducedTraceRegistry.recordConstraint(new ReplayConstraint(
                        ReducedConstraintKind.FIELD_DOMAIN_ORDER,
                        previousDomainEvent.seq(),
                        event.seq(),
                        domain.domainId()));
            }
        }

        ReducedTraceRegistry.save(fileName);
    }

    private static String buildDomainId(int ownerSite, int ownerCount, FieldKey fieldKey) {
        return "field-" + ownerSite + "-" + ownerCount + "-" + fieldKey.owner().internalName()
                + "." + fieldKey.name() + ":" + fieldKey.descriptor();
    }
}
