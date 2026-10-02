package co.ara.onboarding.agreement;

import co.ara.onboarding.journey.MilestoneRepository;
import co.ara.onboarding.journey.RequirementRepository;
import co.ara.onboarding.journey.WriteScopeException;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WriteScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code security.WriteScopeTest}'s shape for agreements: the SIGNATURE requirement's own stage is
 * OWNER_ONLY, so even a holder of every agreement permission at ALL scope is refused unless they
 * are the case owner or the milestone owner. Two such people exist (the case owner, and a
 * reassigned milestone owner) so the four-eyes lifecycle can be driven legitimately; a third
 * administrator, holding everything at ALL, is the refused outsider.
 */
class AgreementWriteScopeTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired AgreementTestSupport support;
    @Autowired AgreementService agreementService;
    @Autowired AgreementReviewService reviewService;
    @Autowired AgreementSignatureService signatureService;
    @Autowired AgreementRepository agreements;
    @Autowired RequirementRepository requirements;
    @Autowired MilestoneRepository milestones;
    @Autowired AgreementVersionRepository versions;
    @Autowired AgreementVersionReviewRepository versionReviews;

    private record Cast(UUID tenant, UUID caseOwner, UUID milestoneOwner, UUID outsider) {}

    private Cast cast(String slug) {
        UUID tenant = fixture.createTenant(slug);
        return new Cast(tenant,
                fixture.createAdministrator(tenant, "case-owner+" + Uuid7.generate() + "@example.com"),
                fixture.createAdministrator(tenant, "ms-owner+" + Uuid7.generate() + "@example.com"),
                fixture.createAdministrator(tenant, "outsider+" + Uuid7.generate() + "@example.com"));
    }

    /** A fresh OWNER_ONLY case owned by {@code caseOwner}, its milestone reassigned to {@code milestoneOwner}. */
    private UUID ownerOnlyCase(Cast c) {
        var caseId = new UUID[1];
        fixture.runAs(c.tenant(), () -> {
            caseId[0] = support.openCase(c.tenant(), WriteScope.OWNER_ONLY, c.caseOwner(), null, null);
            Agreement a = agreements.findByCaseId(caseId[0]).get(0);
            var milestone = milestones.findById(
                    requirements.findById(a.getRequirementId()).orElseThrow().getMilestoneId()).orElseThrow();
            milestone.setOwnerUserId(c.milestoneOwner());
            milestones.saveAndFlush(milestone);
        });
        return caseId[0];
    }

    private AgreementTestSupport.Driven drive(Cast c, UUID caseId, AgreementStatus target) {
        return support.drive(c.tenant(), caseId, target, 1, c.caseOwner(), c.milestoneOwner());
    }

    @Test
    void manageIsRefusedInsideAnOwnerOnlyStage() {
        Cast c = cast("agr-ws-manage");
        var draft = drive(c, ownerOnlyCase(c), AgreementStatus.DRAFT);
        var before = support.snapshot(c.tenant(), draft.agreementId());
        UUID id = draft.agreementId();
        long lock = draft.lockVersion();

        // The guard fires before any status or file-mode check, so uploadDraftFile is WriteScope, not a 409.
        List<Runnable> writes = List.of(
                () -> agreementService.patch(id, new PatchAgreementRequest("Hijacked", null, null, null, null, null, lock)),
                () -> agreementService.replaceSignatories(id, new ReplaceSignatoriesRequest(List.of(), lock)),
                () -> agreementService.uploadDraftFile(id, lock, new ByteArrayInputStream(new byte[] {1}), 1),
                () -> agreementService.submit(id, lock),
                () -> agreementService.cancel(id, new CancelAgreementRequest("Not yours", lock)));
        for (Runnable write : writes) {
            assertThatThrownBy(() -> fixture.runAsUser(c.tenant(), c.outsider(), write))
                    .isInstanceOf(WriteScopeException.class);
        }
        assertThat(support.snapshot(c.tenant(), id)).isEqualTo(before);

        // send is manage's last write; it needs an APPROVED agreement of its own.
        var approved = drive(c, ownerOnlyCase(c), AgreementStatus.APPROVED);
        var approvedBefore = support.snapshot(c.tenant(), approved.agreementId());
        assertThatThrownBy(() -> fixture.runAsUser(c.tenant(), c.outsider(),
                () -> agreementService.send(approved.agreementId(), approved.lockVersion())))
                .isInstanceOf(WriteScopeException.class);
        assertThat(support.snapshot(c.tenant(), approved.agreementId())).isEqualTo(approvedBefore);
    }

    @Test
    void reviewIsRefusedInsideAnOwnerOnlyStage() {
        Cast c = cast("agr-ws-review");
        var underReview = drive(c, ownerOnlyCase(c), AgreementStatus.UNDER_REVIEW);
        var before = support.snapshot(c.tenant(), underReview.agreementId());

        for (ReviewDecision decision : ReviewDecision.values()) {
            assertThatThrownBy(() -> fixture.runAsUser(c.tenant(), c.outsider(), () -> reviewService.review(
                    underReview.agreementId(), 1,
                    new ReviewAgreementRequest(decision, "Because", underReview.lockVersion()))))
                    .isInstanceOf(WriteScopeException.class);
        }

        assertThat(support.snapshot(c.tenant(), underReview.agreementId())).isEqualTo(before);
        fixture.runAs(c.tenant(), () -> {
            List<UUID> versionIds = versions.ofAgreementNewestFirst(underReview.agreementId()).stream()
                    .map(AgreementVersion::getId).toList();
            assertThat(versionReviews.ofVersions(versionIds)).isEmpty();
        });
    }

    @Test
    void signRecordIsRefusedInsideAnOwnerOnlyStage() {
        Cast c = cast("agr-ws-sign");
        // Two signatories: the first signature is not the last, so RequirementService.satisfy (which has its own
        // identical guard) is never reached -- only AgreementWrites' guard can be what refuses this.
        var sent = support.drive(c.tenant(), ownerOnlyCase(c), AgreementStatus.SENT, 2, c.caseOwner(), c.milestoneOwner());
        var before = support.snapshot(c.tenant(), sent.agreementId());

        assertThatThrownBy(() -> fixture.runAsUser(c.tenant(), c.outsider(), () -> signatureService.record(
                sent.agreementId(), new RecordSignatureRequest(sent.signatoryIds().get(0), LocalDate.now(clock),
                        "Wet ink", sent.lockVersion()), null, 0)))
                .isInstanceOf(WriteScopeException.class);

        assertThat(support.snapshot(c.tenant(), sent.agreementId())).isEqualTo(before);
        assertThat(before.signatures()).isZero();
    }

    @Test
    void theCaseOwnerSucceedsInsideTheSameStage() {
        Cast c = cast("agr-ws-owner");
        UUID caseId = ownerOnlyCase(c);
        // drive() itself runs patch, submit, review and send as the owner / milestone owner and must not throw.
        var sent = drive(c, caseId, AgreementStatus.SENT);

        var signed = new AtomicReference<AgreementDetailView>();
        fixture.runAsUser(c.tenant(), c.caseOwner(), () -> signed.set(signatureService.record(
                sent.agreementId(), new RecordSignatureRequest(sent.signatoryIds().get(0), LocalDate.now(clock),
                        "Wet ink", sent.lockVersion()), null, 0)));

        assertThat(signed.get().agreement().status()).isEqualTo(AgreementStatus.SIGNED);
        assertThat(support.snapshot(c.tenant(), sent.agreementId()).signatures()).isEqualTo(1);
    }
}
