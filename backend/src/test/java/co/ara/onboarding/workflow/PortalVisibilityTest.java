package co.ara.onboarding.workflow;

import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.MigrationService;
import co.ara.onboarding.journey.MilestoneRoadmapView;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sub-project 3A, Task 18: {@code milestone_definition.portal_visible}, the
 * milestone-level twin of {@code stage.portal_visible} (QA Q24). Mirrors that
 * field's request/entity/view round trip exactly, with one deliberate departure:
 * {@link WorkflowDefinitionRequest.MilestoneRequest#portalVisible()} is a boxed
 * {@code Boolean} with an explicit null-to-true coalesce in
 * {@code WorkflowService.newMilestone}, not the primitive {@code boolean}
 * {@code StageRequest.portalVisible} still is. A primitive silently binds an
 * omitted JSON key to {@code false} (the exact defect sub-project 2's live run
 * found on {@code StageRequest.autoAdvance}), which for this field would hide a
 * milestone from the customer portal with nobody having chosen that -- the
 * wrong failure direction. See CLAUDE.md's task-18 note for why {@code stage}'s
 * own field is not fixed here.
 */
class PortalVisibilityTest extends PostgresTestBase {

    @Autowired WorkflowService workflows;
    @Autowired PublishService publisher;
    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired RequirementService requirements;
    @Autowired MigrationService migrations;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aMilestoneDefinitionRoundTripsItsPortalVisibleFlag() {
        UUID tenant = fixture.createTenant("portal-visible-roundtrip");
        fixture.runAs(tenant, () -> {
            UUID templateId = workflows.createTemplate("Portal Visibility", "").id();
            UUID draftId = workflows.createDraft(templateId);

            workflows.replaceDraft(draftId, new WorkflowDefinitionRequest(
                    List.of(stage("s1", "Stage One", List.of(
                            milestone("m1", "Milestone One", 1, List.of(),
                                    List.of(manual("Do it")), false)))),
                    List.of(), 0L));

            var definition = workflows.getDefinition(draftId);
            assertThat(definition.stages().get(0).milestones().get(0).portalVisible()).isFalse();
        });
    }

    /**
     * Jackson binds a missing boolean key to false. A milestone that silently
     * became internal-only because a client omitted a field is the wrong failure
     * direction: it would hide work from the customer without anyone choosing
     * to. MilestoneRequest.portalVisible is Boolean with an explicit
     * null-to-true coalesce, not a primitive boolean.
     */
    @Test
    void anOmittedFlagDefaultsToVisibleRatherThanHidden() {
        UUID tenant = fixture.createTenant("portal-visible-default");
        fixture.runAs(tenant, () -> {
            UUID templateId = workflows.createTemplate("Portal Default", "").id();
            UUID draftId = workflows.createDraft(templateId);

            // portalVisible omitted entirely -- the five-arg milestone() helper
            // passes null for it, exactly as a client that never sends the key would.
            workflows.replaceDraft(draftId, new WorkflowDefinitionRequest(
                    List.of(stage("s1", "Stage One", List.of(
                            milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))))),
                    List.of(), 0L));

            var definition = workflows.getDefinition(draftId);
            assertThat(definition.stages().get(0).milestones().get(0).portalVisible()).isTrue();
        });
    }

    /**
     * milestone_definition is a frozen-child table: refuse_published_child_write
     * already refuses every UPDATE once the parent version is not DRAFT. This
     * proves that existing V12 trigger already covers the new column -- no new
     * enforcement is written for this task.
     */
    @Test
    void theFlagCannotBeChangedOnceItsVersionIsPublished() {
        UUID tenant = fixture.createTenant("portal-visible-frozen");
        var milestoneId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID templateId = workflows.createTemplate("Portal Frozen", "").id();
            UUID draftId = workflows.createDraft(templateId);
            workflows.replaceDraft(draftId, new WorkflowDefinitionRequest(
                    List.of(stage("s1", "Stage One", List.of(
                            milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))))),
                    List.of(), 0L));
            publisher.publish(draftId);
            milestoneId.set(workflows.getDefinition(draftId).stages().get(0).milestones().get(0).id());
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> jdbc.update(
                "UPDATE milestone_definition SET portal_visible = false WHERE id = ?", milestoneId.get())))
                .hasMessageContaining("published and cannot be modified");
    }

    /**
     * Q24: one number for every audience. Hiding a milestone from the customer
     * portal must not move the progress bar -- progress stays a duration-weighted
     * rollup over non-SKIPPED milestones (CaseEngine.progressOf), which reads
     * nothing about portal_visible and must go on doing so; Task 21, not this
     * one, is the flag's first renderer.
     */
    @Test
    void progressIgnoresThePortalVisibleFlagEntirely() {
        UUID tenant = fixture.createTenant("portal-visible-progress");
        fixture.runAs(tenant, () -> {
            UUID v1 = journey.publish(new WorkflowDefinitionRequest(
                    List.of(stage("s1", "Stage One", List.of(
                            milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it"))),
                            milestone("m2", "Milestone Two", 1, List.of(), List.of(manual("Do it")))))),
                    List.of(), 0L));
            UUID templateId = journey.templateOf(v1);
            UUID customerId = fixture.createCustomer(
                    tenant, "Portal Visibility Customer " + Uuid7.generate(), null, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, templateId, "Portal Visibility Case " + Uuid7.generate(), Map.of())).id();

            requirements.satisfy(firstRequirementOfMilestone(caseId, 0), null, null);
            int before = cases.get(caseId).progressPercent();
            assertThat(before).isGreaterThan(0);
            assertThat(before).isLessThan(100);

            // v2: identical graph, except m1 is now hidden from the portal.
            UUID v2 = journey.publishNewVersion(templateId, new WorkflowDefinitionRequest(
                    List.of(stage("s1", "Stage One", List.of(
                            milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")), false),
                            milestone("m2", "Milestone Two", 1, List.of(), List.of(manual("Do it")))))),
                    List.of(), 0L));

            migrations.migrate(v2, List.of(caseId));

            assertThat(cases.get(caseId).progressPercent()).isEqualTo(before);
        });
    }

    /**
     * Found live while writing sub-project 3A's task-33 e2e spec:
     * MilestoneRoadmapView never carried portalVisible at all, even though
     * MilestoneRow.tsx (Task 32) already reads milestone.portalVisible to
     * render its "Internal" badge -- a real gap between the frontend's own
     * assumption and the actual generated type, caught only by `next build`'s
     * real tsc pass (vitest's esbuild/swc transpile never type-checks, so the
     * whole frontend suite stayed green while the production build could not
     * compile at all). Fixed by adding the field here, populated from
     * MilestoneDefinition.isPortalVisible() -- the same authoring-time flag
     * aMilestoneDefinitionRoundTripsItsPortalVisibleFlag() above already
     * proves round-trips through the builder.
     */
    @Test
    void theRoadmapCarriesEachMilestonesPortalVisibleFlag() {
        UUID tenant = fixture.createTenant("portal-visible-roadmap");
        fixture.runAs(tenant, () -> {
            UUID v1 = journey.publish(new WorkflowDefinitionRequest(
                    List.of(stage("s1", "Stage One", List.of(
                            milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")), false),
                            milestone("m2", "Milestone Two", 1, List.of(), List.of(manual("Do it")))))),
                    List.of(), 0L));
            UUID templateId = journey.templateOf(v1);
            UUID customerId = fixture.createCustomer(
                    tenant, "Portal Visibility Roadmap Customer " + Uuid7.generate(), null, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, templateId, "Portal Visibility Roadmap Case " + Uuid7.generate(), Map.of())).id();

            assertThat(milestoneOrdinal(caseId, 0).portalVisible()).isFalse();
            assertThat(milestoneOrdinal(caseId, 1).portalVisible()).isTrue();
        });
    }

    private MilestoneRoadmapView milestoneOrdinal(UUID caseId, int flatIndex) {
        return cases.roadmap(caseId).stages().stream()
                .flatMap(s -> s.milestones().stream())
                .toList().get(flatIndex);
    }

    private UUID firstRequirementOfMilestone(UUID caseId, int flatMilestoneIndex) {
        return milestoneOrdinal(caseId, flatMilestoneIndex).requirements().get(0).id();
    }
}
