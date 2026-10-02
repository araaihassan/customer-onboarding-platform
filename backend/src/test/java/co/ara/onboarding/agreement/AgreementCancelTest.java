package co.ara.onboarding.agreement;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditEvent;
import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.document.DocumentRepository;
import co.ara.onboarding.document.VisibilityTier;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.Milestone;
import co.ara.onboarding.journey.Requirement;
import co.ara.onboarding.journey.RequirementRepository;
import co.ara.onboarding.journey.RequirementStatus;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task 17: {@link AgreementService#cancel} -- cancel and replace (spec 5.5). */
class AgreementCancelTest extends PostgresTestBase {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired AgreementTestSupport support;
    @Autowired AgreementService agreementService;
    @Autowired AgreementRepository agreements;
    @Autowired AgreementSignatoryRepository signatories;
    @Autowired DocumentRepository documents;
    @Autowired RequirementRepository requirements;
    @Autowired AuditEventRepository auditEvents;
    @Autowired RoleService roles;

    private record Draft(UUID agreementId, UUID requirementId, UUID caseId, long lockVersion) {}

    /** A DRAFT with one INTERNAL signatory and dates set, on a fresh requirement. */
    private Draft draftWithSignatory(UUID tenant) {
        var ref = new AtomicReference<Draft>();
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            UUID signer = fixture.createUser(tenant, "signer+" + Uuid7.generate() + "@example.com");
            AgreementSignatory s = new AgreementSignatory();
            s.setId(Uuid7.generate());
            s.setTenantId(tenant);
            s.setAgreementId(a.getId());
            s.setKind(SignatoryKind.INTERNAL);
            s.setUserId(signer);
            s.setDisplayRole("Approver");
            s.setSortOrder(0);
            signatories.saveAndFlush(s);
            a.setEffectiveDate(LocalDate.of(2026, 11, 1));
            a.setExpiresAt(LocalDate.of(2027, 11, 1));
            a.setRenewalDate(LocalDate.of(2027, 10, 1));
            a.setNoticePeriodDays(30);
            a = agreements.saveAndFlush(a);
            ref.set(new Draft(a.getId(), a.getRequirementId(), a.getCaseId(), a.getLockVersion()));
        });
        return ref.get();
    }

    private long lockOf(UUID tenant, UUID agreementId) {
        var lock = new AtomicReference<Long>();
        fixture.runAs(tenant, () -> lock.set(agreements.findById(agreementId).orElseThrow().getLockVersion()));
        return lock.get();
    }

    private void uploadFile(UUID tenant, Draft d) {
        fixture.runAsReturning(tenant, () -> agreementService.uploadDraftFile(
                d.agreementId(), d.lockVersion(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));
    }

    private AgreementDetailView cancel(UUID tenant, UUID id, long lock) {
        return fixture.runAsReturning(tenant,
                () -> agreementService.cancel(id, new CancelAgreementRequest("Terms changed", lock)));
    }

    @Test
    void cancellingCreatesADraftSuccessorForTheSameRequirementInOneTransaction() {
        UUID tenant = fixture.createTenant("agr-cancel-successor");
        Draft d = draftWithSignatory(tenant);

        AgreementDetailView next = cancel(tenant, d.agreementId(), d.lockVersion());

        assertThat(next.agreement().id()).isNotEqualTo(d.agreementId());
        assertThat(next.agreement().status()).isEqualTo(AgreementStatus.DRAFT);
        assertThat(next.agreement().requirementId()).isEqualTo(d.requirementId());
        fixture.runAs(tenant, () -> {
            Agreement old = agreements.findById(d.agreementId()).orElseThrow();
            assertThat(old.getStatus()).isEqualTo(AgreementStatus.CANCELLED);
            assertThat(old.getCancelReason()).isEqualTo("Terms changed");
        });
    }

    @Test
    void theSuccessorCopiesNameModeDatesAndSignatoriesButNoDocument() {
        UUID tenant = fixture.createTenant("agr-cancel-copies");
        Draft d = draftWithSignatory(tenant);
        uploadFile(tenant, d);

        AgreementDetailView next = cancel(tenant, d.agreementId(), lockOf(tenant, d.agreementId()));

        fixture.runAs(tenant, () -> {
            Agreement old = agreements.findById(d.agreementId()).orElseThrow();
            assertThat(old.getDocumentId()).isNotNull();
            AgreementView n = next.agreement();
            assertThat(n.name()).isEqualTo(old.getName());
            assertThat(n.recordMode()).isEqualTo(old.getRecordMode());
            assertThat(n.effectiveDate()).isEqualTo(LocalDate.of(2026, 11, 1));
            assertThat(n.expiresAt()).isEqualTo(LocalDate.of(2027, 11, 1));
            assertThat(n.renewalDate()).isEqualTo(LocalDate.of(2027, 10, 1));
            assertThat(n.noticePeriodDays()).isEqualTo(30);
            assertThat(n.documentId()).isNull();
            assertThat(next.signatories()).hasSize(1);
            assertThat(next.signatories().get(0).displayRole()).isEqualTo("Approver");
            assertThat(next.signatories().get(0).signed()).isFalse();
        });
    }

    @Test
    void theSuccessorPointsBackAtWhatItReplaced() {
        UUID tenant = fixture.createTenant("agr-cancel-points-back");
        Draft d = draftWithSignatory(tenant);
        AgreementDetailView next = cancel(tenant, d.agreementId(), d.lockVersion());
        assertThat(next.agreement().replacesAgreementId()).isEqualTo(d.agreementId());
    }

    @Test
    void cancelNeverSatisfiesOrWaivesTheRequirement() {
        UUID tenant = fixture.createTenant("agr-cancel-no-satisfy");
        Draft d = draftWithSignatory(tenant);
        cancel(tenant, d.agreementId(), d.lockVersion());
        fixture.runAs(tenant, () -> {
            Requirement r = requirements.findById(d.requirementId()).orElseThrow();
            assertThat(r.getStatus()).isEqualTo(RequirementStatus.OPEN);
            assertThat(r.getSatisfiedRef()).isNull();
        });
    }

    @Test
    void aSignedAgreementCannotBeCancelled() {
        UUID tenant = fixture.createTenant("agr-cancel-signed");
        Draft d = draftWithSignatory(tenant);
        fixture.runAs(tenant, () -> {
            Agreement a = agreements.findById(d.agreementId()).orElseThrow();
            a.setStatus(AgreementStatus.SIGNED);
            a.setSignedAt(java.time.Instant.now());
            agreements.saveAndFlush(a);
        });
        long lock = lockOf(tenant, d.agreementId());
        assertThatThrownBy(() -> cancel(tenant, d.agreementId(), lock))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aCancelledAgreementCannotBeCancelledAgain() {
        UUID tenant = fixture.createTenant("agr-cancel-twice");
        Draft d = draftWithSignatory(tenant);
        cancel(tenant, d.agreementId(), d.lockVersion());
        long lock = lockOf(tenant, d.agreementId());
        assertThatThrownBy(() -> cancel(tenant, d.agreementId(), lock))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancellingASentAgreementRetiersItsDocumentBackToSensitive() {
        UUID tenant = fixture.createTenant("agr-cancel-sent-retier");
        Draft d = draftWithSignatory(tenant);
        uploadFile(tenant, d);
        var docId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            Agreement a = agreements.findById(d.agreementId()).orElseThrow();
            docId.set(a.getDocumentId());
            Document doc = documents.findById(a.getDocumentId()).orElseThrow();
            doc.setVisibilityTier(VisibilityTier.COMPANY_SHARED);
            documents.saveAndFlush(doc);
            a.setStatus(AgreementStatus.SENT);
            agreements.saveAndFlush(a);
        });

        cancel(tenant, d.agreementId(), lockOf(tenant, d.agreementId()));

        fixture.runAs(tenant, () -> assertThat(documents.findById(docId.get()).orElseThrow().getVisibilityTier())
                .isEqualTo(VisibilityTier.SENSITIVE));
    }

    @Test
    void cancellingADraftNeverSentLeavesItsDocumentSensitive() {
        UUID tenant = fixture.createTenant("agr-cancel-draft-doc");
        Draft d = draftWithSignatory(tenant);
        uploadFile(tenant, d);
        var docId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> docId.set(agreements.findById(d.agreementId()).orElseThrow().getDocumentId()));

        cancel(tenant, d.agreementId(), lockOf(tenant, d.agreementId()));

        fixture.runAs(tenant, () -> assertThat(documents.findById(docId.get()).orElseThrow().getVisibilityTier())
                .isEqualTo(VisibilityTier.SENSITIVE));
    }

    @Test
    void exactlyOneLiveAgreementRemainsForTheRequirement() {
        UUID tenant = fixture.createTenant("agr-cancel-one-live");
        Draft d = draftWithSignatory(tenant);
        AgreementDetailView next = cancel(tenant, d.agreementId(), d.lockVersion());
        fixture.runAs(tenant, () -> {
            List<Agreement> forCase = agreements.findByCaseId(d.caseId());
            assertThat(forCase).hasSize(2);
            assertThat(forCase.stream().filter(a -> a.getStatus() != AgreementStatus.CANCELLED))
                    .extracting(Agreement::getId).containsExactly(next.agreement().id());
            assertThat(agreements.liveFor(d.requirementId())).isPresent();
        });
    }

    @Test
    void cancelRecordsCancelledThenCreatedInThatOrder() {
        UUID tenant = fixture.createTenant("agr-cancel-audit-order");
        Draft d = draftWithSignatory(tenant);
        cancel(tenant, d.agreementId(), d.lockVersion());
        fixture.runAs(tenant, () -> {
            List<AuditEvent> events = auditEvents.findAll().stream()
                    .filter(e -> AuditActions.AGREEMENT_CANCELLED.key().equals(e.getAction())
                            || AuditActions.AGREEMENT_CREATED.key().equals(e.getAction()))
                    .sorted(Comparator.comparing(AuditEvent::getOccurredAt))
                    .toList();
            assertThat(events).extracting(AuditEvent::getAction)
                    .containsExactly(AuditActions.AGREEMENT_CANCELLED.key(), AuditActions.AGREEMENT_CREATED.key());
        });
    }

    /** Narrowest scope (CLAUDE.md): a TEAM-scoped agreement.manage holder cancelling on their team's case. */
    @Test
    void aTeamScopedManagerCanCancelOnTheirTeamsCase() {
        UUID tenant = fixture.createTenant("agr-cancel-team-scope");
        var actor = new UUID[1];
        var ids = new UUID[1];
        var lock = new long[1];
        fixture.runAs(tenant, () -> {
            UUID teamId = fixture.createTeam(tenant, "Delivery Team " + Uuid7.generate());
            actor[0] = fixture.createUser(tenant, "team-scoped+" + Uuid7.generate() + "@example.com");
            fixture.addToTeam(tenant, actor[0], teamId);
            UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.AGREEMENT_MANAGE, Scope.TEAM,
                    PermissionKeys.AGREEMENT_VIEW, Scope.TEAM,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
            roles.assignRole(actor[0], role);
            Case c = journey.newCase(tenant, null, null, teamId);
            Milestone m = journey.newMilestone(tenant, c);
            Requirement r = journey.newRequirement(tenant, c, m);
            Agreement a = support.draftAgreementRowFor(tenant, c.getId(), r.getId());
            ids[0] = a.getId();
            lock[0] = a.getLockVersion();
        });

        var result = new AtomicReference<AgreementDetailView>();
        fixture.runAsUser(tenant, actor[0], () -> result.set(agreementService.cancel(
                ids[0], new CancelAgreementRequest("Narrow cancel", lock[0]))));

        assertThat(result.get().agreement().status()).isEqualTo(AgreementStatus.DRAFT);
        assertThat(result.get().agreement().replacesAgreementId()).isEqualTo(ids[0]);
    }
}
