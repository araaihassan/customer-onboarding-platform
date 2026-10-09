package co.ara.onboarding.agreement;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.document.AgreementFiles;
import co.ara.onboarding.journey.Requirement;
import co.ara.onboarding.journey.RequirementRepository;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.journey.RequirementStatus;
import co.ara.onboarding.platform.Uuid7;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Recording one signatory's signature (Task 16; spec 5.5/5.6) and, on the last one, satisfying
 * the SIGNATURE requirement through the existing gated {@code RequirementService.satisfy} --
 * no new {@code CaseEngine}/{@code lockById} caller.
 */
@Service
public class AgreementSignatureService {

    public static final String SATISFIED_REF_TYPE = "AGREEMENT";

    private final AgreementWrites writes;
    private final AgreementRepository agreements;
    private final AgreementSignatoryRepository signatories;
    private final AgreementSignatureRepository signatures;
    private final AgreementVersionRepository versions;
    private final RequirementRepository requirements;
    private final RequirementService requirementService;
    private final AgreementFiles agreementFiles;
    private final AgreementService agreementService;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final Clock clock;
    private final AuditRecorder audit;
    private final ApplicationEventPublisher events;

    public AgreementSignatureService(AgreementWrites writes, AgreementRepository agreements,
                                     AgreementSignatoryRepository signatories,
                                     AgreementSignatureRepository signatures, AgreementVersionRepository versions,
                                     RequirementRepository requirements, RequirementService requirementService,
                                     AgreementFiles agreementFiles, AgreementService agreementService,
                                     AuthorizedQuery authorizedQuery, AuthContextProvider contextProvider,
                                     Clock clock, AuditRecorder audit, ApplicationEventPublisher events) {
        this.writes = writes;
        this.agreements = agreements;
        this.signatories = signatories;
        this.signatures = signatures;
        this.versions = versions;
        this.requirements = requirements;
        this.requirementService = requirementService;
        this.agreementFiles = agreementFiles;
        this.agreementService = agreementService;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @RequirePermission(PermissionKeys.AGREEMENT_SIGN_RECORD)
    @Transactional
    public AgreementDetailView record(UUID agreementId, RecordSignatureRequest r,
                                      InputStream countersigned, long countersignedSize) {
        Agreement a = writes.loadForWrite(agreementId, PermissionKeys.AGREEMENT_SIGN_RECORD, r.lockVersion(),
                EnumSet.of(AgreementStatus.SENT, AgreementStatus.AWAITING_SIGNATURE));
        if (r.signedOn().isAfter(LocalDate.now(clock))) {
            throw new IllegalArgumentException("A signature cannot be dated in the future");
        }

        // Resolved as a signatory OF THIS AGREEMENT; anything else is simply not found.
        AgreementSignatory party = authorizedQuery.getById(signatories, AgreementSignatory.class,
                PermissionKeys.AGREEMENT_SIGN_RECORD, r.signatoryId());
        if (!party.getAgreementId().equals(a.getId())) throw new NoSuchElementException("Not found");

        List<AgreementSignature> existing = signatures.ofAgreement(a.getId());
        if (existing.stream().anyMatch(s -> s.getSignatoryId().equals(party.getId()))) {
            throw new IllegalStateException("That signatory's signature is already recorded");
        }
        boolean last = existing.size() + 1 == signatories.ofAgreement(a.getId()).size();

        if (countersigned != null && (!last || !a.getRecordMode().includesFile())) {
            throw new IllegalArgumentException(
                    "A countersigned file is accepted only with the last signature of an agreement that includes a file");
        }
        if (last && a.getRecordMode().includesFile() && countersigned == null) {
            throw new IllegalArgumentException("The last signature of this agreement needs the countersigned file");
        }

        AgreementVersion sent = versions.ofAgreementNewestFirst(a.getId()).get(0);
        UUID countersignedVersionId = countersigned == null ? null
                : agreementFiles.addCountersignedVersion(a.getDocumentId(), countersigned, countersignedSize)
                        .documentVersionId();

        UUID actor = contextProvider.current().userId();
        Instant now = Instant.now(clock);
        signatures.saveAndFlush(new AgreementSignature(Uuid7.generate(), a.getTenantId(), a.getId(), party.getId(),
                sent.getId(), sent.getContentSha256(), r.signedOn(), r.method(), actor, now, countersignedVersionId));
        audit.record(AuditActions.AGREEMENT_SIGNATURE_RECORDED, "onboarding_case", a.getCaseId(),
                "Recorded " + party.getDisplayRole() + "'s signature on " + a.getName(),
                Map.of("agreementId", a.getId().toString(), "signatoryId", party.getId().toString(),
                        "signedContentSha256", sent.getContentSha256()));

        a.setStatus(last ? AgreementStatus.SIGNED : AgreementStatus.AWAITING_SIGNATURE);
        if (last) a.setSignedAt(now);
        a.setUpdatedAt(now);
        agreements.saveAndFlush(a);

        if (last) {
            // Cause before effect: agreement.signed precedes requirement.satisfied and
            // milestone.completed, which satisfy() records.
            audit.record(AuditActions.AGREEMENT_SIGNED, "onboarding_case", a.getCaseId(),
                    "Signed " + a.getName(), Map.of("agreementId", a.getId().toString()));
            events.publishEvent(new AgreementStatusChanged(a.getId(), a.getCaseId(),
                    AgreementStatusChanged.Change.SIGNED, actor));
            satisfyIfStillOpen(a);
        }
        return agreementService.get(a.getId());
    }

    /**
     * Spec 5.6. satisfy() is idempotent only for SATISFIED -- on a WAIVED requirement it
     * would overwrite the waiver -- so the status is read first. Only a SIGNED agreement,
     * which the partial unique index makes the requirement's single live one, satisfies it.
     * Any failure inside satisfy (CaseOnHoldException, a missing milestone.complete) rolls
     * this whole method back; the countersigned blob already written is orphaned, which is
     * accepted -- BlobStore has no delete by design (sub-project 4 spec 7.1).
     */
    private void satisfyIfStillOpen(Agreement a) {
        Requirement r = authorizedQuery.getById(requirements, Requirement.class,
                PermissionKeys.AGREEMENT_SIGN_RECORD, a.getRequirementId());
        if (r.getStatus() != RequirementStatus.OPEN) return;
        requirementService.satisfy(a.getRequirementId(), a.getId(), SATISFIED_REF_TYPE);
    }
}
