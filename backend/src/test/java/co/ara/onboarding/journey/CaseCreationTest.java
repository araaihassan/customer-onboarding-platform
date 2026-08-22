package co.ara.onboarding.journey;

import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseCreationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;

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
                    Map.of()));

            var roadmap = cases.roadmap(view.id());
            assertThat(roadmap.stages()).hasSize(3);
            assertThat(roadmap.stages().get(0).milestones()).hasSize(2);
            assertThat(roadmap.stages().get(0).milestones().get(0).requirements()).hasSize(1);
            assertThat(view.status()).isEqualTo(CaseStatus.ACTIVE);
            assertThat(view.progressPercent()).isZero();
        });
    }

    /** Q15: the OWNER participant is the default milestone owner. */
    @Test
    void theCustomersOwnerBecomesTheOwnerParticipantAndEveryMilestonesOwner() {
        UUID tenant = fixture.createTenant("case-owner");
        fixture.runAs(tenant, () -> {
            UUID owner = fixture.createUser(tenant, "owner@example.com");
            UUID customerId = fixture.createCustomerOwnedBy(tenant, "Acme", owner);
            var view = cases.create(new CreateCaseRequest(customerId, journey.publishedTemplate(), Map.of()));

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

            var view = cases.create(new CreateCaseRequest(customerId, journey.publishedTemplate(), Map.of()));

            assertThat(view.ownerUserId()).isEqualTo(owner);
            assertThat(view.owningDepartmentId()).isEqualTo(department);
            assertThat(view.owningTeamId()).isEqualTo(team);
        });
    }

    /**
     * Due dates are computed once, when a stage is entered -- and stage entry is
     * CaseEngine.advanceIfExitable, still a deliberate no-op stub as of this task
     * (Task 15 fills it in). So immediately after creation, before any stage has been
     * entered, no milestone has a due date yet; a case created on any day of the week
     * is identical on this point. The business-day accumulation this test's name
     * describes is exercised once Task 15 lands stage entry.
     */
    @Test
    void dueDatesAccumulateInBusinessDaysWithinAStage() {
        UUID tenant = fixture.createTenant("case-dates");
        fixture.runAs(tenant, () -> {
            UUID versionId = journey.publishedThreeStageWorkflow();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            var view = cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId), Map.of()));

            var firstStageMilestones = cases.roadmap(view.id()).stages().get(0).milestones();
            assertThat(firstStageMilestones).allSatisfy(m -> assertThat(m.dueDate()).isNull());
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
                cases.create(new CreateCaseRequest(customerId.get(), templateId.get(), Map.of()))))
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
                customerId.get(), templateId.get(), Map.of("segment", "MIDMARKET")))))
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
                Map.of("segment", "ENTERPRISE", "employeeCount", "not-a-number")))))
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
                new CreateCaseRequest(customerId.get(), templateId.get(), Map.of()))))
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
                new CreateCaseRequest(tenantBCustomerId.get(), templateId.get(), Map.of()))))
                .isInstanceOf(NoSuchElementException.class);
    }

    /** Two cases on one customer are normal: the switcher exists because they coexist. */
    @Test
    void aCustomerCanHaveConcurrentCases() {
        UUID tenant = fixture.createTenant("case-concurrent");
        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID templateId = journey.publishedTemplate();

            var first = cases.create(new CreateCaseRequest(customerId, templateId, Map.of()));
            var second = cases.create(new CreateCaseRequest(customerId, templateId, Map.of()));

            assertThat(first.id()).isNotEqualTo(second.id());
            assertThat(cases.listForCustomer(customerId)).extracting("id")
                    .containsExactlyInAnyOrder(first.id(), second.id());
        });
    }
}
