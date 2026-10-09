package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.MilestoneCompleted;
import co.ara.onboarding.journey.StageEntered;
import co.ara.onboarding.journey.StageExited;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** MILESTONE_COMPLETED (spec 5.2) and the template-driven stage alerts (spec 5.5). */
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

    @EventListener public void on(StageEntered e) { stageAlert(e.caseId(), e.stageId(), e.actorId(), true); }
    @EventListener public void on(StageExited e)  { stageAlert(e.caseId(), e.stageId(), e.actorId(), false); }

    private void stageAlert(UUID caseId, UUID stageId, UUID actorId, boolean entered) {
        var stage = facts.stage(stageId);
        if (stage.templateKey() == null || stage.templateKey().isBlank()) return;         // keyed stages only
        var template = facts.activeTemplate(stage.templateKey());
        if (template.isEmpty()) return;                                                    // missing or inactive
        String subject = entered ? template.get().enteredSubject() : template.get().exitedSubject();
        String body = entered ? template.get().enteredBody() : template.get().exitedBody();
        if (subject == null) return;                                                       // entry-only template
        var kase = facts.caseFacts(caseId);
        Map<String, String> values = Map.of("case", kase.name(), "customer", kase.customerName(),
                "stage", stage.name(), "owner", facts.userName(kase.ownerUserId()));
        var draft = new NotificationPipeline.Draft(NotificationType.STAGE_CHANGED, "stage", stage.id(), kase.id(),
                TemplatePlaceholders.render(subject, values), TemplatePlaceholders.render(body, values),
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), Tone.INFO, null);
        pipeline.deliver(draft, facts.caseAudience(kase.id()), actorId,
                new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, kase.id()));
    }
}
