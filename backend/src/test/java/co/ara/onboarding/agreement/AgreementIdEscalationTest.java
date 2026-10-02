package co.ara.onboarding.agreement;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WriteScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The write-path half of CLAUDE.md's id-escalation rule: a passing {@code @RequirePermission}
 * gate proves only that the actor may touch SOME agreement, so every id taken from a request body
 * (a signatory's contact/user, a signatory id, a version number) must itself be resolved through
 * the actor's scope and tied back to THIS agreement. Each negative is paired with a control that
 * succeeds, so the refusal can be attributed to the id and not to a broken fixture.
 */
class AgreementIdEscalationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired AgreementTestSupport support;
    @Autowired AgreementService agreementService;
    @Autowired AgreementReviewService reviewService;
    @Autowired AgreementSignatureService signatureService;
    @Autowired AgreementVersionRepository versions;
    @Autowired AgreementVersionReviewRepository versionReviews;
    @Autowired RoleService roles;

    @Test
    void aContactOfAnotherCustomerInTheSameTenantIsNotFound() {
        UUID tenant = fixture.createTenant("agr-esc-contact");
        UUID admin = fixture.createAdministrator(tenant, "admin+" + Uuid7.generate() + "@example.com");
        var draft = new Agreement[1];
        var ownContact = new UUID[1];
        var foreignContact = new UUID[1];
        fixture.runAs(tenant, () -> {
            draft[0] = support.draftAgreementRow(tenant);
            UUID otherCustomer = fixture.createCustomer(tenant, "Other " + Uuid7.generate(), null, null, null);
            ownContact[0] = fixture.createContact(tenant, draft[0].getCustomerId(), "own+" + Uuid7.generate() + "@example.com");
            foreignContact[0] = fixture.createContact(tenant, otherCustomer, "foreign+" + Uuid7.generate() + "@example.com");
        });
        UUID id = draft[0].getId();
        long lock = draft[0].getLockVersion();
        var before = support.snapshot(tenant, id);

        // The actor holds contact.view at ALL, so the contact IS visible to them: only the customer tie refuses it.
        assertThatThrownBy(() -> fixture.runAsUser(tenant, admin, () -> agreementService.replaceSignatories(id,
                new ReplaceSignatoriesRequest(List.of(new SignatoryRequest(
                        SignatoryKind.CONTACT, foreignContact[0], null, "Signer")), lock))))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(support.snapshot(tenant, id)).isEqualTo(before);

        // Control: the agreement's own customer's contact is accepted.
        var ok = new AtomicReference<AgreementDetailView>();
        fixture.runAsUser(tenant, admin, () -> ok.set(agreementService.replaceSignatories(id,
                new ReplaceSignatoriesRequest(List.of(new SignatoryRequest(
                        SignatoryKind.CONTACT, ownContact[0], null, "Signer")), lock))));
        assertThat(ok.get().signatories()).hasSize(1);
    }

    @Test
    void anInternalUserOutsideTheActorsUserViewScopeIsNotFound() {
        UUID tenant = fixture.createTenant("agr-esc-user");
        var actor = new UUID[1];
        var sameDept = new UUID[1];
        var otherDept = new UUID[1];
        var draft = new Agreement[1];
        fixture.runAs(tenant, () -> {
            UUID mine = fixture.createDepartment(tenant, "Mine " + Uuid7.generate());
            UUID theirs = fixture.createDepartment(tenant, "Theirs " + Uuid7.generate());
            actor[0] = fixture.createUserInDepartment(tenant, "actor+" + Uuid7.generate() + "@example.com", mine);
            sameDept[0] = fixture.createUserInDepartment(tenant, "mate+" + Uuid7.generate() + "@example.com", mine);
            otherDept[0] = fixture.createUserInDepartment(tenant, "other+" + Uuid7.generate() + "@example.com", theirs);
            // Agreement rights at ALL, but user.view only at DEPARTMENT: the nominated user must resolve in-scope.
            roles.assignRole(actor[0], roles.createRole("Fixture Role " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.AGREEMENT_VIEW, Scope.ALL,
                    PermissionKeys.AGREEMENT_MANAGE, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL,
                    PermissionKeys.USER_VIEW, Scope.DEPARTMENT)));
            draft[0] = support.draftAgreementRow(tenant);
        });
        UUID id = draft[0].getId();
        long lock = draft[0].getLockVersion();
        var before = support.snapshot(tenant, id);

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () -> agreementService.replaceSignatories(id,
                new ReplaceSignatoriesRequest(List.of(new SignatoryRequest(
                        SignatoryKind.INTERNAL, null, otherDept[0], "Signer")), lock))))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(support.snapshot(tenant, id)).isEqualTo(before);

        // Control: a colleague in the actor's own department resolves.
        var ok = new AtomicReference<AgreementDetailView>();
        fixture.runAsUser(tenant, actor[0], () -> ok.set(agreementService.replaceSignatories(id,
                new ReplaceSignatoriesRequest(List.of(new SignatoryRequest(
                        SignatoryKind.INTERNAL, null, sameDept[0], "Signer")), lock))));
        assertThat(ok.get().signatories()).hasSize(1);
    }

    @Test
    void aSignatoryIdFromAnotherAgreementIsNotFoundWhenRecording() {
        UUID tenant = fixture.createTenant("agr-esc-signatory");
        var caseA = new UUID[1];
        var caseB = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseA[0] = support.openCase(tenant, WriteScope.ANY, null, null, null);
            caseB[0] = support.openCase(tenant, WriteScope.ANY, null, null, null);
        });
        var a = support.drive(tenant, caseA[0], AgreementStatus.SENT, 1, null, null);
        var b = support.drive(tenant, caseB[0], AgreementStatus.SENT, 1, null, null);
        UUID recorder = fixture.createAdministrator(tenant, "recorder+" + Uuid7.generate() + "@example.com");
        var aBefore = support.snapshot(tenant, a.agreementId());
        var bBefore = support.snapshot(tenant, b.agreementId());

        // B's signatory is fully visible to this ALL-scoped recorder; only the agreement tie refuses it.
        assertThatThrownBy(() -> fixture.runAsUser(tenant, recorder, () -> signatureService.record(a.agreementId(),
                new RecordSignatureRequest(b.signatoryIds().get(0), LocalDate.now(clock), "Wet ink", a.lockVersion()),
                null, 0)))
                .isInstanceOf(NoSuchElementException.class);

        assertThat(support.snapshot(tenant, a.agreementId())).isEqualTo(aBefore);
        assertThat(support.snapshot(tenant, b.agreementId())).isEqualTo(bBefore);

        // Control: its own signatory records fine.
        var ok = new AtomicReference<AgreementDetailView>();
        fixture.runAsUser(tenant, recorder, () -> ok.set(signatureService.record(a.agreementId(),
                new RecordSignatureRequest(a.signatoryIds().get(0), LocalDate.now(clock), "Wet ink", a.lockVersion()),
                null, 0)));
        assertThat(ok.get().agreement().status()).isEqualTo(AgreementStatus.SIGNED);
    }

    @Test
    void aVersionNumberOfAnotherAgreementCannotBeReviewedHere() {
        UUID tenant = fixture.createTenant("agr-esc-version");
        var caseA = new UUID[1];
        var caseB = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseA[0] = support.openCase(tenant, WriteScope.ANY, null, null, null);
            caseB[0] = support.openCase(tenant, WriteScope.ANY, null, null, null);
        });
        UUID submitter = fixture.createAdministrator(tenant, "submitter+" + Uuid7.generate() + "@example.com");
        UUID reviewer = fixture.createAdministrator(tenant, "reviewer+" + Uuid7.generate() + "@example.com");
        var a = support.drive(tenant, caseA[0], AgreementStatus.UNDER_REVIEW, 1, submitter, reviewer);
        var b = support.drive(tenant, caseB[0], AgreementStatus.UNDER_REVIEW, 1, submitter, reviewer);

        // Give B a second version: reject v1, then resubmit it as v2. A only ever has v1.
        var bAfterReject = new AtomicReference<AgreementDetailView>();
        fixture.runAsUser(tenant, reviewer, () -> bAfterReject.set(reviewService.review(b.agreementId(), 1,
                new ReviewAgreementRequest(ReviewDecision.REJECT, "Redo", b.lockVersion()))));
        var bResubmitted = new AtomicReference<AgreementDetailView>();
        fixture.runAsUser(tenant, submitter, () -> bResubmitted.set(agreementService.submit(
                b.agreementId(), bAfterReject.get().agreement().lockVersion())));
        assertThat(bResubmitted.get().versions()).hasSize(2);

        var aBefore = support.snapshot(tenant, a.agreementId());
        var bBefore = support.snapshot(tenant, b.agreementId());

        // "v2" exists, but on B. Reviewed against A it is just a version A does not have -- refused, never retargeted.
        assertThatThrownBy(() -> fixture.runAsUser(tenant, reviewer, () -> reviewService.review(a.agreementId(), 2,
                new ReviewAgreementRequest(ReviewDecision.APPROVE, null, a.lockVersion()))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(support.snapshot(tenant, a.agreementId())).isEqualTo(aBefore);
        assertThat(support.snapshot(tenant, b.agreementId())).isEqualTo(bBefore);
        fixture.runAs(tenant, () -> {
            List<UUID> bLatest = versions.ofAgreementNewestFirst(b.agreementId()).stream()
                    .limit(1).map(AgreementVersion::getId).toList();
            assertThat(versionReviews.ofVersions(bLatest)).isEmpty();
            List<UUID> aVersions = versions.ofAgreementNewestFirst(a.agreementId()).stream()
                    .map(AgreementVersion::getId).toList();
            assertThat(versionReviews.ofVersions(aVersions)).isEmpty();
        });

        // Control: A's own v1 is reviewable by the same actor.
        var ok = new AtomicReference<AgreementDetailView>();
        fixture.runAsUser(tenant, reviewer, () -> ok.set(reviewService.review(a.agreementId(), 1,
                new ReviewAgreementRequest(ReviewDecision.APPROVE, null, a.lockVersion()))));
        assertThat(ok.get().agreement().status()).isEqualTo(AgreementStatus.APPROVED);
    }
}
