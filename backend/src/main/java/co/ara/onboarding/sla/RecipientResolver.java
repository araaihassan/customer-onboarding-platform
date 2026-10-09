package co.ara.onboarding.sla;

import co.ara.onboarding.identity.ReportingLineDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Spec 6.2: late person's manager, else department head, else administrators; inactive people are
 * skipped, and so is the late person -- except when they are the tenant's only active administrator, who
 * is then the recipient rather than nobody (an escalation is never silently undelivered).
 */
@Component
public class RecipientResolver {

    public record Resolution(EscalationRoute route, List<ReportingLineDirectory.Recipient> recipients) {
        /** The single recipient for MANAGER / DEPARTMENT_HEAD, else null. */
        public UUID primaryUserId() {
            return route == EscalationRoute.ADMINISTRATORS || recipients.isEmpty() ? null : recipients.get(0).userId();
        }
    }

    private final ReportingLineDirectory people;

    public RecipientResolver(ReportingLineDirectory people) { this.people = people; }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Resolution resolve(UUID latePersonId) {
        if (latePersonId != null) {
            var manager = people.activeManagerOf(latePersonId).filter(r -> !r.userId().equals(latePersonId));
            if (manager.isPresent()) return new Resolution(EscalationRoute.MANAGER, List.of(manager.get()));
            var head = people.activeDepartmentHeadOf(latePersonId).filter(r -> !r.userId().equals(latePersonId));
            if (head.isPresent()) return new Resolution(EscalationRoute.DEPARTMENT_HEAD, List.of(head.get()));
        }
        // Never the late person about their own lateness -- unless they are the tenant's only
        // active administrator, in which case they are the only person who can act (6B spec 5.4,
        // closing sub-project 6 spec 6.2's amendment). An empty list now means no administrator exists.
        var admins = people.activeAdministrators();
        var others = admins.stream().filter(r -> !r.userId().equals(latePersonId)).toList();
        return new Resolution(EscalationRoute.ADMINISTRATORS, others.isEmpty() ? admins : others);
    }
}
