package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.MilestoneCompleted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** MILESTONE_COMPLETED (spec 5.2); Task 20 adds the stage events. */
@Component
public class JourneyNotifications {

    private final NotificationPipeline pipeline;
    private final SubjectFacts facts;

    JourneyNotifications(NotificationPipeline pipeline, SubjectFacts facts) {
        this.pipeline = pipeline;
        this.facts = facts;
    }

    /**
     * The case audience (owner plus ACTIVE participants) and the milestone's own owner. The
     * completer is excluded by the pipeline, and every candidate must still hold case.view on it.
     */
    @EventListener
    public void on(MilestoneCompleted e) {
        var m = facts.milestone(e.milestoneId());
        var kase = facts.caseFacts(e.caseId());
        Set<UUID> candidates = new LinkedHashSet<>(facts.caseAudience(kase.id()));
        if (m.ownerUserId() != null) candidates.add(m.ownerUserId());
        var draft = new NotificationPipeline.Draft(NotificationType.MILESTONE_COMPLETED, "milestone", m.id(), kase.id(),
                (e.forced() ? "Milestone force-completed: " : "Milestone completed: ") + Text.clip(m.name(), 80),
                kase.name() + " (" + kase.customerName() + ")",
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), Tone.OK, null);
        pipeline.deliver(draft, candidates, e.actorId(),
                new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, kase.id()));
    }
}
