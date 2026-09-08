package co.ara.onboarding.journey;

import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.RequirementKind;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.RequirementRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseCreationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired BusinessCalendar calendar;

    /**
     * Everything the roadmap needs exists from the first moment. The prototype draws all
     * nine stages with future ones pending, so a lazily-built roadmap has nothing to
     * render -- and eager instantiation is also what lets Task 14 compute a denominator.
     */
    @Test
    void creatingACaseInstantiatesEveryMilestoneAndRequirement() {
        UUID tenant = fixture.createTenant("case-create");
        fixture.runAs(tenant, () -> {
            UUID versionId = journey.publishedThreeStageWorkflow();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);

            var view = cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId),
                    "Fixture Case " + Uuid7.generate(), Map.of()));

            var roadmap = cases.roadmap(view.id());
            assertThat(roadmap.stages()).hasSize(3);
            assertThat(roadmap.stages().get(0).milestones()).hasSize(2);
            assertThat(roadmap.stages().get(0).milestones().get(0).requirements()).hasSize(1);
            assertThat(view.status()).isEqualTo(CaseStatus.ACTIVE);
            assertThat(view.progressPercent()).isZero();
        });
    }

    /**
     * A milestone with no mandatory requirement must not complete merely by
     * existing. Reproduced live: CaseEngine.recomputeStatusesAndProgress checked
     * mandatorySettled() before reachability, so a milestone in a stage the case
     * has never entered -- here, stage two, while the case sits in stage one --
     * was markDone()'d on the very first reconcile create() triggers, purely
     * because it has nothing mandatory to block it.
     */
    @Test
    void aMilestoneWithNoMandatoryRequirementDoesNotCompleteBeforeItsStageIsReached() {
        UUID tenant = fixture.createTenant("case-premature-done");
        fixture.runAs(tenant, () -> {
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(
                    stage("s1", "Stage One", List.of(
                            milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it"))))),
                    stage("s2", "Stage Two", List.of(
                            milestone("m2", "Optional-only Milestone", 1, List.of(),
                                    List.of(new RequirementRequest(
                                            RequirementKind.MANUAL, "Nice to have", 1, false, null, null)))))),
                    List.of(), 0L));
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);

            var view = cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(), Map.of()));

            var roadmap = cases.roadmap(view.id());
            assertThat(roadmap.stages().get(0).milestones().get(0).status()).isEqualTo(MilestoneStatus.ACTIVE);
            assertThat(roadmap.stages().get(1).milestones().get(0).status())
                    .isNotEqualTo(MilestoneStatus.DONE);
        });
    }

    /** Q15: the OWNER participant is the default milestone owner. */
    @Test
    void theCustomersOwnerBecomesTheOwnerParticipantAndEveryMilestonesOwner() {
        UUID tenant = fixture.createTenant("case-owner");
        fixture.runAs(tenant, () -> {
            UUID owner = fixture.createUser(tenant, "owner@example.com");
            UUID customerId = fixture.createCustomerOwnedBy(tenant, "Acme", owner);
            var view = cases.create(new CreateCaseRequest(customerId, journey.publishedTemplate(), "Fixture Case " + Uuid7.generate(), Map.of()));

            assertThat(cases.participants(view.id()))
                    .anySatisfy(p -> {
                        assertThat(p.userId()).isEqualTo(owner);
                        assertThat(p.relationship()).isEqualTo(RelationshipType.OWNER);
                    });
            assertThat(cases.roadmap(view.id()).stages().get(0).milestones().get(0).ownerUserId())
                    .isEqualTo(owner);
        });
    }

    /**
     * Ownership is copied, not joined. It is what the case and milestone descriptors
     * resolve DEPARTMENT and TEAM against, and copying is what keeps journey from
     * needing a customer join to answer an authorization question.
     */
    @Test
    void ownershipIsCopiedFromTheCustomer() {
        UUID tenant = fixture.createTenant("case-ownership-copy");
        fixture.runAs(tenant, () -> {
            UUID owner = fixture.createUser(tenant, "owner2@example.com");
            UUID department = fixture.createDepartment(tenant, "Onboarding");
            UUID team = fixture.createTeam(tenant, "Onboarding Team");
            UUID customerId = fixture.createCustomer(tenant, "Acme", owner, department, team);

            var view = cases.create(new CreateCaseRequest(customerId, journey.publishedTemplate(), "Fixture Case " + Uuid7.generate(), Map.of()));

            assertThat(view.ownerUserId()).isEqualTo(owner);
            assertThat(view.owningDepartmentId()).isEqualTo(department);
            assertThat(view.owningTeamId()).isEqualTo(team);
        });
    }

    /**
     * Due dates are computed once, when a stage is entered -- and CaseService.create's
     * own reconcile() call enters the first stage immediately (Task 15's
     * CaseEngine.advanceIfExitable), so the first stage's milestones are scheduled
     * before create() ever returns. publishedThreeStageWorkflow's first stage has
     * milestones of duration 2 then 3, cumulative within the stage: the first is due
     * 2 business days out, the second 5.
     */
    @Test
    void dueDatesAccumulateInBusinessDaysWithinAStage() {
        UUID tenant = fixture.createTenant("case-dates");
        fixture.runAs(tenant, () -> {
            UUID versionId = journey.publishedThreeStageWorkflow();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            var view = cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(), Map.of()));

            var firstStageMilestones = cases.roadmap(view.id()).stages().get(0).milestones();
            assertThat(firstStageMilestones).hasSize(2);
            assertThat(firstStageMilestones.get(0).dueDate())
                    .isEqualTo(calendar.plusBusinessDays(java.time.LocalDate.now(), 2));
            assertThat(firstStageMilestones.get(1).dueDate())
                    .isEqualTo(calendar.plusBusinessDays(java.time.LocalDate.now(), 5));
        });
    }

    @Test
    void aMissingRequiredAttributeIsRejected() {
        UUID tenant = fixture.createTenant("case-attr-missing");
        var templateId = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            templateId.set(journey.publishedTemplateWithSegmentAttribute());
            customerId.set(fixture.createCustomer(tenant, "Acme", null, null, null));
        });

        // Never assert inside the runAs lambda: fixture.runAs wraps the action in a
        // TransactionTemplate, and catching the exception there leaves the
        // transaction rollback-only, surfacing UnexpectedRollbackException instead
        // of the exception under test. Wrap the whole runAs call instead.
        assertThatThrownBy(() -> fixture.runAs(tenant, () ->
                cases.create(new CreateCaseRequest(customerId.get(), templateId.get(), "Fixture Case " + Uuid7.generate(), Map.of()))))
                .isInstanceOf(AttributeValidationException.class)
                .hasMessageContaining("segment");
    }

    @Test
    void anAttributeValueOutsideItsAllowedValuesIsRejected() {
        UUID tenant = fixture.createTenant("case-attr-invalid-enum");
        var templateId = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            templateId.set(journey.publishedTemplateWithSegmentAttribute());
            customerId.set(fixture.createCustomer(tenant, "Acme", null, null, null));
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> cases.create(new CreateCaseRequest(
                customerId.get(), templateId.get(), "Fixture Case " + Uuid7.generate(), Map.of("segment", "MIDMARKET")))))
                .isInstanceOf(AttributeValidationException.class)
                .hasMessageContaining("segment");
    }

    @Test
    void aNonNumericValueForANumberAttributeIsRejected() {
        UUID tenant = fixture.createTenant("case-attr-non-numeric");
        var templateId = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            templateId.set(journey.publishedTemplateWithSegmentAttribute());
            customerId.set(fixture.createCustomer(tenant, "Acme", null, null, null));
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> cases.create(new CreateCaseRequest(
                customerId.get(), templateId.get(),
                "Fixture Case " + Uuid7.generate(), Map.of("segment", "ENTERPRISE", "employeeCount", "not-a-number")))))
                .isInstanceOf(AttributeValidationException.class)
                .hasMessageContaining("employeeCount");
    }

    @Test
    void aTemplateWithNoPublishedVersionCannotStartACase() {
        UUID tenant = fixture.createTenant("case-unpublished");
        var templateId = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            templateId.set(journey.draftOnlyTemplate());
            customerId.set(fixture.createCustomer(tenant, "Acme", null, null, null));
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> cases.create(
                new CreateCaseRequest(customerId.get(), templateId.get(), "Fixture Case " + Uuid7.generate(), Map.of()))))
                .isInstanceOf(TemplateNotPublishedException.class);
    }

    /** The oracle again, now at the case-creation boundary: 404, never 200 and never 500. */
    @Test
    void anotherTenantsCustomerIdIsANotFound() {
        UUID tenantA = fixture.createTenant("case-cross-a");
        UUID tenantB = fixture.createTenant("case-cross-b");
        var tenantBCustomerId = new AtomicReference<UUID>();
        var templateId = new AtomicReference<UUID>();
        fixture.runAs(tenantB, () ->
                tenantBCustomerId.set(fixture.createCustomer(tenantB, "Beta", null, null, null)));
        fixture.runAs(tenantA, () -> templateId.set(journey.publishedTemplate()));

        assertThatThrownBy(() -> fixture.runAs(tenantA, () -> cases.create(
                new CreateCaseRequest(tenantBCustomerId.get(), templateId.get(), "Fixture Case " + Uuid7.generate(), Map.of()))))
                .isInstanceOf(NoSuchElementException.class);
    }

    /** Q18: a journey carries a human-readable name, set at creation. */
    @Test
    void aCaseCanBeCreatedWithAName() {
        UUID tenant = fixture.createTenant("case-named");
        fixture.runAs(tenant, () -> {
            UUID templateId = journey.publishedTemplate();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);

            var view = cases.create(new CreateCaseRequest(customerId, templateId,
                    "Enterprise onboarding", Map.of()));

            assertThat(view.name()).isEqualTo("Enterprise onboarding");
            assertThat(cases.get(view.id()).name()).isEqualTo("Enterprise onboarding");
        });
    }

    /**
     * Fix round 1: CreateCaseDialog now always collects a name, so
     * CreateCaseRequest.name is @NotBlank -- there is no fallback left to
     * protect a nameless create with (the reviewer's own point: the removed
     * "template name + short id" fallback was worse than the bug Q18 exists
     * to close, since it read as real but was identical across every case
     * opened from the same template). Bean validation runs at the controller
     * (@Valid), so this exercises the service directly with an already-blank
     * value, the same shape the bean-validation annotation itself checks --
     * see JourneyApiTest for the end-to-end 400 through the controller.
     */
    @Test
    void aBlankNameFailsBeanValidation() {
        var violations = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator()
                .validate(new CreateCaseRequest(UUID.randomUUID(), UUID.randomUUID(), "  ", Map.of()));
        assertThat(violations).isNotEmpty();
    }

    /** Two cases on one customer are normal: the switcher exists because they coexist. */
    @Test
    void aCustomerCanHaveConcurrentCases() {
        UUID tenant = fixture.createTenant("case-concurrent");
        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID templateId = journey.publishedTemplate();

            var first = cases.create(new CreateCaseRequest(customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of()));
            var second = cases.create(new CreateCaseRequest(customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of()));

            assertThat(first.id()).isNotEqualTo(second.id());
            assertThat(cases.listForCustomer(customerId)).extracting("id")
                    .containsExactlyInAnyOrder(first.id(), second.id());
        });
    }
}
