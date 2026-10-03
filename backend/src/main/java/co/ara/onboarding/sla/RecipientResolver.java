package co.ara.onboarding.sla;

import co.ara.onboarding.identity.ReportingLineDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Spec 6.2. Task 16 completes the chain; this first version routes everything to administrators. */
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
        return new Resolution(EscalationRoute.ADMINISTRATORS, people.activeAdministrators());
    }
}
