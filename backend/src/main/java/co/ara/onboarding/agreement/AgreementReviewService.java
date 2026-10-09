package co.ara.onboarding.agreement;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.platform.Uuid7;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

/**
 * The mandatory four-eyes review of a submitted {@link AgreementVersion} (Task 14; spec
 * sections 4.5/5.3). {@link AgreementWrites#loadForWrite} does the shared prologue --
 * resolves through {@code AuthorizedQuery} under {@code agreement.review}, refuses a
 * stale {@code lockVersion} as a 409, refuses any status but UNDER_REVIEW as a 409, and
 * narrows on top with the SIGNATURE requirement's own stage write scope.
 *
 * <p>Only the agreement's own latest version may ever be reviewed -- a request naming an
 * earlier version number is refused ({@link IllegalStateException}), never silently
 * re-targeted onto the current one.
 *
 * <p>The self-review rule ({@link SelfReviewException}, spec 5.3 / invariant 7) is
 * checked here, and only here: whoever submitted this version, or last edited it before
 * submission, cannot be the one who decides it, even holding {@code agreement.review}
 * at the widest scope.
 *
 * <p>APPROVE moves the agreement to APPROVED; REJECT moves it back to DRAFT and needs a
 * non-blank {@code reason} ({@link IllegalArgumentException} otherwise). Either way the
 * decision is written as its own append-only {@link AgreementVersionReview} row before
 * the agreement's own status changes -- the same "evidence first" ordering {@link
 * AgreementService#submit} already uses for its own {@link AgreementVersion} row.
 */
@Service
public class AgreementReviewService {

    private final AgreementWrites writes;
    private final AgreementRepository agreements;
    private final AgreementVersionRepository versions;
    private final AgreementVersionReviewRepository reviews;
    private final AuthContextProvider contextProvider;
    private final AgreementService agreementService;
    private final Clock clock;
    private final AuditRecorder audit;
    private final ApplicationEventPublisher events;

    public AgreementReviewService(AgreementWrites writes, AgreementRepository agreements,
                                  AgreementVersionRepository versions, AgreementVersionReviewRepository reviews,
                                  AuthContextProvider contextProvider, AgreementService agreementService,
                                  Clock clock, AuditRecorder audit, ApplicationEventPublisher events) {
        this.writes = writes;
        this.agreements = agreements;
        this.versions = versions;
        this.reviews = reviews;
        this.contextProvider = contextProvider;
        this.agreementService = agreementService;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @RequirePermission(PermissionKeys.AGREEMENT_REVIEW)
    @Transactional
    public AgreementDetailView review(UUID agreementId, int versionNumber, ReviewAgreementRequest r) {
        Agreement a = writes.loadForWrite(agreementId, PermissionKeys.AGREEMENT_REVIEW, r.lockVersion(),
                EnumSet.of(AgreementStatus.UNDER_REVIEW));
        AgreementVersion latest = versions.ofAgreementNewestFirst(a.getId()).get(0);
        if (latest.getVersionNumber() != versionNumber) {
            throw new IllegalStateException("Only the latest version (v" + latest.getVersionNumber() + ") can be reviewed");
        }
        UUID reviewer = contextProvider.current().userId();
        // Spec 5.3 / invariant 7: enforced HERE, never only in the UI.
        if (reviewer.equals(latest.getSubmittedBy()) || reviewer.equals(latest.getLastEditedBy())) {
            throw new SelfReviewException(a.getId(), latest.getVersionNumber());
        }
        if (r.decision() == ReviewDecision.REJECT && (r.reason() == null || r.reason().isBlank())) {
            throw new IllegalArgumentException("A rejection needs a reason");
        }
        Instant now = Instant.now(clock);
        reviews.saveAndFlush(new AgreementVersionReview(Uuid7.generate(), a.getTenantId(), latest.getId(),
                r.decision(), reviewer, now, r.reason()));

        boolean approved = r.decision() == ReviewDecision.APPROVE;
        a.setStatus(approved ? AgreementStatus.APPROVED : AgreementStatus.DRAFT);
        a.setUpdatedAt(now);
        agreements.saveAndFlush(a);

        audit.record(approved ? AuditActions.AGREEMENT_APPROVED : AuditActions.AGREEMENT_REJECTED,
                "onboarding_case", a.getCaseId(),
                (approved ? "Approved " : "Rejected ") + a.getName() + " v" + versionNumber,
                Map.of("agreementId", a.getId().toString(), "versionNumber", Integer.toString(versionNumber)));
        events.publishEvent(new AgreementStatusChanged(a.getId(), a.getCaseId(),
                approved ? AgreementStatusChanged.Change.APPROVED : AgreementStatusChanged.Change.REJECTED, reviewer));
        return agreementService.get(a.getId());
    }
}
