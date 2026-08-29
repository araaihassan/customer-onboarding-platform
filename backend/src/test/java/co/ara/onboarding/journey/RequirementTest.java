package co.ara.onboarding.journey;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequirementTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired RequirementService requirements;
    @Autowired CaseRepository caseRepository;
    @Autowired AuditEventRepository auditEvents;

    @Test
    void satisfyingTheLastMandatoryRequirementCompletesTheMilestone() {
        UUID tenant = fixture.createTenant("req-complete");
        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, journey.publishedTemplate(), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            UUID requirementId = firstRequirementId(caseId);

            requirements.satisfy(requirementId, null, null);

            var milestone = cases.roadmap(caseId).stages().get(0).milestones().get(0);
            assertThat(milestone.status()).isEqualTo(MilestoneStatus.DONE);
        });
    }

    @Test
    void satisfyingIsIdempotentForAnAlreadySatisfiedRequirement() {
        UUID tenant = fixture.createTenant("req-idempotent");
        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, journey.publishedTemplate(), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            UUID requirementId = firstRequirementId(caseId);

            requirements.satisfy(requirementId, null, null);
            long countAfterFirst = countByAction(AuditActions.REQUIREMENT_SATISFIED.key());

            requirements.satisfy(requirementId, null, null);
            long countAfterSecond = countByAction(AuditActions.REQUIREMENT_SATISFIED.key());

            assertThat(countAfterSecond).isEqualTo(countAfterFirst);
        });
    }

    @Test
    void waivingRequiresAReason() {
        UUID tenant = fixture.createTenant("req-waive-reason");
        var requirementId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, journey.publishedTemplate(), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            requirementId.set(firstRequirementId(caseId));
        });

        // Never assert inside the runAs lambda -- catching there leaves the
        // transaction rollback-only and surfaces UnexpectedRollbackException
        // instead of the exception under test. Wrap the whole runAs call instead.
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> requirements.waive(requirementId.get(), "  ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWaivedRequirementSettlesTheMilestoneAndIsAudited() {
        UUID tenant = fixture.createTenant("req-waive");
        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, journey.publishedTemplate(), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            UUID requirementId = firstRequirementId(caseId);

            var view = requirements.waive(requirementId, "Not applicable for this customer");

            assertThat(view.status()).isEqualTo(RequirementStatus.WAIVED);
            assertThat(view.waiverReason()).isEqualTo("Not applicable for this customer");

            var milestone = cases.roadmap(caseId).stages().get(0).milestones().get(0);
            assertThat(milestone.status()).isEqualTo(MilestoneStatus.DONE);

            assertThat(auditEvents.findAll())
                    .anySatisfy(e -> assertThat(e.getAction()).isEqualTo(AuditActions.REQUIREMENT_WAIVED.key()));
        });
    }

    @Test
    void satisfyingIsRefusedWhileTheCaseIsOnHold() {
        UUID tenant = fixture.createTenant("req-on-hold");
        var requirementId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, journey.publishedTemplate(), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            requirementId.set(firstRequirementId(caseId));

            // Task 18 owns the hold/resume transition; this test only needs the
            // status set to prove satisfy() refuses it. No hold() method exists yet.
            Case c = caseRepository.findById(caseId).orElseThrow();
            c.setStatus(CaseStatus.ON_HOLD);
            caseRepository.saveAndFlush(c);
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () ->
                requirements.satisfy(requirementId.get(), null, null)))
                .isInstanceOf(CaseOnHoldException.class);
    }

    /**
     * The seam sub-projects 3-5 use. A satisfied requirement records WHAT satisfied
     * it, with no foreign key, so a task or document id can be recorded before
     * those tables exist.
     */
    @Test
    void aSatisfyingReferenceIsRecordedWithoutAForeignKey() {
        UUID tenant = fixture.createTenant("req-satisfying-ref");
        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, journey.publishedTemplate(), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            UUID requirementId = firstRequirementId(caseId);

            UUID pretendTaskId = Uuid7.generate();
            var view = requirements.satisfy(requirementId, pretendTaskId, "task");

            assertThat(view.satisfiedRef()).isEqualTo(pretendTaskId);
            assertThat(view.satisfiedRefType()).isEqualTo("task");
        });
    }

    private UUID firstRequirementId(UUID caseId) {
        return cases.roadmap(caseId).stages().get(0).milestones().get(0).requirements().get(0).id();
    }

    private long countByAction(String actionKey) {
        return auditEvents.findAll().stream().filter(e -> e.getAction().equals(actionKey)).count();
    }
}
