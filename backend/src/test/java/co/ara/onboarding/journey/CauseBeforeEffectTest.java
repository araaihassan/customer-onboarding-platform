package co.ara.onboarding.journey;

import co.ara.onboarding.audit.AuditEventView;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An action's own audit event must be recorded BEFORE the events describing
 * what that action triggered. AuditRecorder stamps occurredAt from the clock,
 * so call order IS timeline order -- see its javadoc for the full rule.
 *
 * Every one of these assertions failed before the fix: nine call sites across
 * five journey services recorded their cause after engine.reconcile(), so a
 * newest-first timeline showed each cause sitting above its own effects. The
 * user's report was "milestones completed before the case is assigned to a
 * user, which is wrong" -- read off the screen, because no test looked at the
 * relative order of two DIFFERENT actions.
 *
 * These assert on ORDER ONLY, never on the number of events, so a sub-project
 * that adds a new event to any of these paths does not have to edit this file.
 */
class CauseBeforeEffectTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired RequirementService requirements;
    @Autowired TimelineService timeline;

    @Test
    void creatingACaseIsRecordedBeforeTheStageEntryAndMilestonesItCauses() {
        UUID tenant = fixture.createTenant("cbe-create");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCase(tenant);

            // Oldest-first: the case is opened, and only then does anything
            // happen inside it.
            assertThat(chronological(caseId))
                    .startsWith("case.created")
                    .containsSubsequence("case.created", "case.stage_entered");
        });
    }

    @Test
    void satisfyingARequirementIsRecordedBeforeTheMilestoneItCompletes() {
        UUID tenant = fixture.createTenant("cbe-satisfy");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCase(tenant);
            requirements.satisfy(firstRequirementId(caseId), null, null);

            // The completion is a CONSEQUENCE of the satisfaction, so it cannot
            // precede it. Before the fix these were the other way round.
            assertThat(chronological(caseId))
                    .containsSubsequence("requirement.satisfied", "milestone.completed");
        });
    }

    @Test
    void aCompletedCaseIsRecordedAfterTheRequirementThatCompletedIt() {
        UUID tenant = fixture.createTenant("cbe-complete");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCase(tenant);
            requirements.satisfy(firstRequirementId(caseId), null, null);   // completes the case

            assertThat(chronological(caseId))
                    .containsSubsequence("requirement.satisfied", "case.completed")
                    .endsWith("case.completed");
        });
    }

    /**
     * Hold and resume are the ordering case that does NOT go through a
     * reconcile-writes-effects path in this fixture, so it is here as the
     * control: it should have read correctly before the fix and still does.
     */
    @Test
    void holdAndResumeReadInTheOrderTheyHappened() {
        UUID tenant = fixture.createTenant("cbe-resume");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCase(tenant);
            cases.hold(caseId, "pausing");
            cases.resume(caseId);

            assertThat(chronological(caseId))
                    .containsSubsequence("case.created", "case.held", "case.resumed");
        });
    }

    /**
     * The timeline read is oldest-first (see AuditEventRepository), so this is
     * the order things actually happened, unreversed. Kept as a named helper
     * rather than inlined: if the read direction is ever flipped back, this is
     * the single place these order assertions need to adapt.
     */
    private List<String> chronological(UUID caseId) {
        return timeline.forCase(caseId, Pageable.ofSize(100)).getContent().stream()
                .map(AuditEventView::action)
                .toList();
    }

    private UUID openCase(UUID tenant) {
        UUID templateId = journey.publishedTemplate();
        UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
        return cases.create(new CreateCaseRequest(customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }

    private UUID firstRequirementId(UUID caseId) {
        return cases.roadmap(caseId).stages().get(0).milestones().get(0).requirements().get(0).id();
    }
}
