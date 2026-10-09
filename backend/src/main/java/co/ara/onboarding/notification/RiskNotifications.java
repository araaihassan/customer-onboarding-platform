package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.journey.Case;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** RISK_CHANGED (6B spec 5.2/6.1, Q19): the case audience, gated per recipient by case.view. */
@Component
public class RiskNotifications {

    private final NotificationPipeline pipeline;
    private final SubjectFacts facts;

    RiskNotifications(NotificationPipeline pipeline, SubjectFacts facts) {
        this.pipeline = pipeline;
        this.facts = facts;
    }

    @EventListener
    public void on(RiskChanged e) {
        var kase = facts.caseFacts(e.caseId());
        var stage = facts.stage(e.stageId());
        boolean breached = e.state() == RiskChanged.State.BREACHED;
        String body = breached
                ? "Stage " + stage.name() + " passed its " + e.targetDays() + "-business-day SLA."
                : "Stage " + stage.name() + ": " + String.format(Locale.ROOT, "%.1f", e.remainingDays())
                  + " of " + e.targetDays() + " business days remain.";
        var draft = new NotificationPipeline.Draft(NotificationType.RISK_CHANGED, "sla_clock", e.clockId(), kase.id(),
                Text.clip(kase.name(), 80) + (breached ? " breached its SLA" : " is at risk"), body,
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), breached ? Tone.RISK : Tone.WARN,
                "RISK:" + e.clockId() + ":" + e.state());
        pipeline.deliver(draft, facts.caseAudience(kase.id()), null,
                new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, kase.id()));
    }
}
