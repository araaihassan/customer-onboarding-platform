package co.ara.onboarding.agreement;

import co.ara.onboarding.audit.AuditEventView;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.MigrationService;
import co.ara.onboarding.journey.TimelineService;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.AgreementRecordMode;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.signature;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Task 10: opening a case creates one DRAFT agreement per SIGNATURE
 * requirement -- the SIGNATURE half of the same requirement seam
 * {@code document.DocumentInstantiationTest} already proves for DOCUMENT,
 * modelled on that test's own cases, plus the ones this port's second caller
 * (MigrationService) requires: idempotency under a direct second call, and
 * migration both creating a new SIGNATURE requirement's agreement and leaving
 * an existing live one alone.
 */
class AgreementInstantiationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired MigrationService migrations;
    @Autowired AgreementInstantiation instantiation;
    @Autowired AgreementRepository agreements;
    @Autowired AgreementTestSupport support;
    @Autowired AuthContextProvider contextProvider;
    @Autowired TimelineService timeline;

    @Test
    void openingACaseCreatesOneDraftAgreementPerSignatureRequirement() {
        UUID tenant = fixture.createTenant("agr-inst-basic");
        fixture.runAs(tenant, () -> {
            UUID caseId = support.openCaseWithSignatureRequirement(tenant, AgreementRecordMode.FILE_BACKED);

            assertThat(agreements.findByCaseId(caseId)).singleElement()
                    .satisfies(a -> {
                        assertThat(a.getRequirementId()).isNotNull();
                        assertThat(a.getStatus()).isEqualTo(AgreementStatus.DRAFT);
                        assertThat(a.getSignatureProvider()).isEqualTo(SignatureProviderKind.MANUAL);
                    });
        });
    }

    @Test
    void theAgreementCopiesNameAndRecordModeFromTheDefinitionAndCustomerFromTheCase() {
        UUID tenant = fixture.createTenant("agr-inst-copy");
        fixture.runAs(tenant, () -> {
            UUID caseId = support.openCaseWithSignatureRequirement(tenant, AgreementRecordMode.STRUCTURED_ONLY);

            Agreement a = agreements.findByCaseId(caseId).get(0);
            assertThat(a.getName()).isEqualTo("Fixture Agreement");
            assertThat(a.getRecordMode()).isEqualTo(AgreementRecordMode.STRUCTURED_ONLY);
            assertThat(a.getCustomerId()).isEqualTo(cases.get(caseId).customerId());
        });
    }

    /** Q15's own default, the identical fallback DocumentInstantiation/TaskInstantiation already use. */
    @Test
    void theOwnerIsTheMilestoneOwnerFallingBackToTheActingPrincipal() {
        UUID tenant = fixture.createTenant("agr-inst-owner");
        fixture.runAs(tenant, () -> {
            UUID owner = fixture.createUser(tenant, "owner@example.com");
            UUID caseWithOwner = openCaseOwnedBy(tenant, owner);
            assertThat(agreements.findByCaseId(caseWithOwner).get(0).getOwnerUserId()).isEqualTo(owner);

            UUID actor = contextProvider.principal().userId();
            UUID caseWithoutOwner = openCaseOwnedBy(tenant, null);
            assertThat(agreements.findByCaseId(caseWithoutOwner).get(0).getOwnerUserId()).isEqualTo(actor);
        });
    }

    @Test
    void requirementsOfOtherKindsProduceNoAgreement() {
        UUID tenant = fixture.createTenant("agr-inst-manual-only");
        fixture.runAs(tenant, () -> {
            // publishedTemplate() is a single stage/milestone/MANUAL requirement,
            // no SIGNATURE requirement anywhere in the graph.
            UUID templateId = journey.publishedTemplate();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id();

            assertThat(agreements.findByCaseId(caseId)).isEmpty();
        });
    }

    /**
     * Unlike DocumentInstantiation, which leaves a second call to fail against
     * document_request_requirement_uq: MigrationService re-calls this port after
     * every repin, so a second call on a case whose SIGNATURE requirements
     * already have live agreements must be a genuine no-op.
     */
    @Test
    void callingItTwiceIsANoOpNotADuplicateOrAnError() {
        UUID tenant = fixture.createTenant("agr-inst-twice");
        fixture.runAs(tenant, () -> {
            UUID caseId = support.openCaseWithSignatureRequirement(tenant, AgreementRecordMode.FILE_BACKED);
            assertThat(agreements.findByCaseId(caseId)).hasSize(1);
            UUID firstAgreementId = agreements.findByCaseId(caseId).get(0).getId();

            assertThatCode(() -> instantiation.instantiateForCase(caseId)).doesNotThrowAnyException();

            List<Agreement> after = agreements.findByCaseId(caseId);
            assertThat(after).hasSize(1);
            assertThat(after.get(0).getId()).isEqualTo(firstAgreementId);
        });
    }

    @Test
    void migratingToAVersionThatAddsASignatureRequirementCreatesItsAgreement() {
        UUID tenant = fixture.createTenant("agr-inst-migrate-add");
        fixture.runAs(tenant, () -> {
            UUID v1 = journey.publish(new WorkflowDefinitionRequest(
                    List.of(stage("s1", "Stage One", List.of(
                            milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))))),
                    List.of(), 0L));
            UUID templateId = journey.templateOf(v1);
            UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id();
            assertThat(agreements.findByCaseId(caseId)).isEmpty();

            // v2 keeps stage one untouched and appends a second stage carrying a
            // SIGNATURE requirement -- a brand new milestone the case has not yet
            // reached, so migration instantiates it fresh (migrateOne's own
            // "not matched" branch).
            UUID v2 = journey.publishNewVersion(templateId, new WorkflowDefinitionRequest(
                    List.of(stage("s1", "Stage One", List.of(
                                    milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it"))))),
                            stage("s2", "Stage Two", List.of(
                                    milestone("m2", "Milestone Two", 1, List.of(),
                                            List.of(signature("Sign it", AgreementRecordMode.FILE_BACKED,
                                                    "New Agreement")))))),
                    List.of(), 0L));

            migrations.migrate(v2, List.of(caseId));

            assertThat(agreements.findByCaseId(caseId)).singleElement()
                    .satisfies(a -> {
                        assertThat(a.getName()).isEqualTo("New Agreement");
                        assertThat(a.getStatus()).isEqualTo(AgreementStatus.DRAFT);
                    });
        });
    }

    @Test
    void migratingLeavesAnExistingLiveAgreementAlone() {
        UUID tenant = fixture.createTenant("agr-inst-migrate-leave");
        fixture.runAs(tenant, () -> {
            UUID caseId = support.openCaseWithSignatureRequirement(tenant, AgreementRecordMode.FILE_BACKED);
            UUID templateId = cases.get(caseId).templateId();
            Agreement before = agreements.findByCaseId(caseId).get(0);

            // v2's stage/milestone/requirement carry the identical name/label, so
            // migrateOne's remapRequirements matches and remaps the SAME
            // Requirement row rather than instantiating a new one -- the agreement's
            // own requirementId, and therefore the agreement itself, is unaffected.
            UUID v2 = journey.publishNewVersion(templateId, new WorkflowDefinitionRequest(
                    List.of(stage("s1", "Stage One", List.of(
                            milestone("m1", "Milestone One", 1, List.of(),
                                    List.of(signature("Sign the agreement", AgreementRecordMode.FILE_BACKED,
                                            "Fixture Agreement")))))),
                    List.of(), 0L));

            migrations.migrate(v2, List.of(caseId));

            List<Agreement> after = agreements.findByCaseId(caseId);
            assertThat(after).hasSize(1);
            assertThat(after.get(0).getId()).isEqualTo(before.getId());
            assertThat(after.get(0).getStatus()).isEqualTo(AgreementStatus.DRAFT);
        });
    }

    @Test
    void aCancelledAgreementDoesNotCountAsLiveForInstantiation() {
        UUID tenant = fixture.createTenant("agr-inst-cancelled");
        fixture.runAs(tenant, () -> {
            UUID caseId = support.openCaseWithSignatureRequirement(tenant, AgreementRecordMode.FILE_BACKED);
            Agreement live = agreements.findByCaseId(caseId).get(0);
            live.setStatus(AgreementStatus.CANCELLED);
            live.setCancelReason("Fixture cancellation");
            live.setUpdatedAt(Instant.now());
            agreements.saveAndFlush(live);
            assertThat(agreements.liveFor(live.getRequirementId())).isEmpty();

            instantiation.instantiateForCase(caseId);

            List<Agreement> all = agreements.findByCaseId(caseId);
            assertThat(all).hasSize(2);
            assertThat(all).extracting(Agreement::getStatus)
                    .containsExactlyInAnyOrder(AgreementStatus.CANCELLED, AgreementStatus.DRAFT);
        });
    }

    @Test
    void creationIsAuditedAfterCaseCreated() {
        UUID tenant = fixture.createTenant("agr-inst-audit");
        fixture.runAs(tenant, () -> {
            UUID caseId = support.openCaseWithSignatureRequirement(tenant, AgreementRecordMode.FILE_BACKED);

            List<String> actions = timeline.forCase(caseId, Pageable.ofSize(100)).getContent().stream()
                    .map(AuditEventView::action).toList();
            assertThat(actions).containsSubsequence("case.created", "agreement.created");
        });
    }

    private UUID openCaseOwnedBy(UUID tenant, UUID ownerUserId) {
        UUID versionId = journey.publish(new WorkflowDefinitionRequest(
                List.of(stage("s1", "Stage One", List.of(
                        milestone("m1", "Milestone One", 1, List.of(),
                                List.of(signature("Sign the agreement", AgreementRecordMode.FILE_BACKED,
                                        "Fixture Agreement " + Uuid7.generate())))))),
                List.of(), 0L));
        UUID templateId = journey.templateOf(versionId);
        UUID customerId = ownerUserId == null
                ? fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null)
                : fixture.createCustomerOwnedBy(tenant, "Acme " + Uuid7.generate(), ownerUserId);
        return cases.create(new CreateCaseRequest(
                customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }
}
