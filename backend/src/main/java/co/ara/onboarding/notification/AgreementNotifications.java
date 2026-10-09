package co.ara.onboarding.notification;

import co.ara.onboarding.agreement.Agreement;
import co.ara.onboarding.agreement.AgreementStatusChanged;
import co.ara.onboarding.authz.PermissionKeys;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AGREEMENT_STATUS (spec 5.2, plan amendment 1): submit, approve/reject, send, the LAST signature
 * and cancel reach the agreement's owner and the case owner, gated agreement.view on the agreement
 * itself so RecipientAccess applies the agreement audience filter and record scope.
 */
@Component
public class AgreementNotifications {

    private static final Map<AgreementStatusChanged.Change, String> WORDS = Map.of(
            AgreementStatusChanged.Change.SUBMITTED, "Submitted for review",
            AgreementStatusChanged.Change.APPROVED, "Approved",
            AgreementStatusChanged.Change.REJECTED, "Rejected",
            AgreementStatusChanged.Change.SENT, "Sent for signature",
            AgreementStatusChanged.Change.SIGNED, "Signed",
            AgreementStatusChanged.Change.CANCELLED, "Cancelled");

    private final NotificationPipeline pipeline;
    private final SubjectFacts facts;

    AgreementNotifications(NotificationPipeline pipeline, SubjectFacts facts) {
        this.pipeline = pipeline;
        this.facts = facts;
    }

    @EventListener
    public void on(AgreementStatusChanged e) {
        var a = facts.agreement(e.agreementId());
        var kase = facts.caseFacts(e.caseId());
        Tone tone = switch (e.change()) {
            case APPROVED, SIGNED -> Tone.OK;
            case REJECTED, CANCELLED -> Tone.RISK;
            default -> Tone.INFO;
        };
        Set<UUID> candidates = new LinkedHashSet<>();
        if (a.ownerUserId() != null) candidates.add(a.ownerUserId());
        if (kase.ownerUserId() != null) candidates.add(kase.ownerUserId());
        var draft = new NotificationPipeline.Draft(NotificationType.AGREEMENT_STATUS, "agreement", a.id(), kase.id(),
                Text.clip(a.name(), 80) + ": " + WORDS.get(e.change()), kase.name() + " (" + kase.customerName() + ")",
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), tone, null);
        pipeline.deliver(draft, candidates, e.actorId(),
                new NotificationPipeline.Visibility(PermissionKeys.AGREEMENT_VIEW, Agreement.class, a.id()));
    }
}
