package co.ara.onboarding.journey;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.MilestoneDefinitionRepository;
import co.ara.onboarding.workflow.PublishService;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
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

/**
 * Sub-project 3A, Task 27: {@link PlanRevisionService#diff}.
 *
 * Both revisions being compared are constructed directly against {@link
 * PlanRevisionRepository}/{@link PlanRevisionItemRepository} rather than through
 * {@link PlanRevisionService#issue} -- the same "seed the state directly, no
 * production path needed" pattern {@code PlanRevisionTest} already uses for its
 * own held/approved-case fixtures. A milestone leaving the portal-visible plan
 * between two real {@code issue()} calls cannot be produced through the service
 * layer at all: {@code milestone_definition_frozen} (V12) refuses every write to
 * a published version's {@code milestone_definition} row, {@code portalVisible}
 * included -- so the only way to prove {@code diff} reports a dropped milestone
 * as REMOVED is to hand it two snapshots that already disagree, exactly as two
 * real, chronologically issued revisions would look once read back.
 *
 * {@code pm} holds only {@code plan.issue} at TEAM, the narrowest catalogued
 * scope for that key, and both cases below share {@code pm}'s own team as their
 * owning team.
 */
class PlanRevisionDiffTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired PlanRevisionService planRevisionService;
    @Autowired PlanRevisionRepository revisions;
    @Autowired PlanRevisionItemRepository items;
    @Autowired MilestoneRepository milestones;
    @Autowired MilestoneDefinitionRepository milestoneDefinitions;
    @Autowired CaseService caseService;
    @Autowired WorkflowService workflows;
    @Autowired PublishService publishService;
    @Autowired RoleService roles;

    private UUID tenant;
    private UUID team;
    private UUID pm;
    private UUID caseId;
    private UUID rev1;
    private UUID rev2;
    private UUID revisionOfAnotherCase;

    @BeforeEach
    void seedTwoRevisionsWithADateChangeAndADroppedMilestone() {
        tenant = fixture.createTenant("plan-revision-diff-" + Uuid7.generate());

        fixture.runAs(tenant, () -> {
            team = fixture.createTeam(tenant, "Diff Team " + Uuid7.generate());
            pm = fixture.createUser(tenant, "pm+" + Uuid7.generate() + "@plan-revision-diff.example");
            fixture.addToTeam(tenant, pm, team);
            UUID pmRole = roles.createRole("Diff PM " + Uuid7.generate(), "",
                    Map.of(PermissionKeys.PLAN_ISSUE, Scope.TEAM));
            roles.assignRole(pm, pmRole);

            CaseAndVersion main = openThreeMilestoneCase(team);
            caseId = main.caseId();

            Milestone kickoff = milestoneRow(caseId, main.versionId(), "Kickoff");
            Milestone internal = milestoneRow(caseId, main.versionId(), "Internal staging");
            Milestone goLive = milestoneRow(caseId, main.versionId(), "Go live");

            rev1 = Uuid7.generate();
            saveRevision(rev1, caseId, 1, PlanRevisionStatus.SUPERSEDED);
            items.save(itemRow(rev1, caseId, kickoff, "Kickoff", LocalDate.of(2026, 10, 1), 1));
            items.save(itemRow(rev1, caseId, internal, "Internal staging", LocalDate.of(2026, 10, 5), 2));
            items.save(itemRow(rev1, caseId, goLive, "Go live", LocalDate.of(2026, 10, 20), 3));

            rev2 = Uuid7.generate();
            saveRevision(rev2, caseId, 2, PlanRevisionStatus.ISSUED);
            items.save(itemRow(rev2, caseId, kickoff, "Kickoff", LocalDate.of(2026, 10, 15), 1));
            // "Internal staging" is absent here -- it left the portal-visible plan
            // between rev1 and rev2.
            items.save(itemRow(rev2, caseId, goLive, "Go live", LocalDate.of(2026, 10, 20), 2));

            CaseAndVersion other = openThreeMilestoneCase(team);
            revisionOfAnotherCase = Uuid7.generate();
            saveRevision(revisionOfAnotherCase, other.caseId(), 1, PlanRevisionStatus.ISSUED);
        });
    }

    @Test
    void aShiftedDateShowsAsDateChangedWithBothValues() {
        PlanRevisionDiffView d = runAs(pm, () -> planRevisionService.diff(rev2, rev1));

        assertThat(rowFor(d, "Kickoff").changeKind()).isEqualTo(ChangeKind.DATE_CHANGED);
        assertThat(rowFor(d, "Kickoff").previousDueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(rowFor(d, "Kickoff").currentDueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    void aMilestoneMadeInternalBetweenRevisionsShowsAsRemoved() {
        // It left the customer-visible plan. That is a change the sponsor is entitled
        // to see, not a silent disappearance.
        PlanRevisionDiffView d = runAs(pm, () -> planRevisionService.diff(rev2, rev1));

        assertThat(rowFor(d, "Internal staging").changeKind()).isEqualTo(ChangeKind.REMOVED);
    }

    @Test
    void diffingARevisionAgainstItselfReportsEveryRowUnchanged() {
        PlanRevisionDiffView d = runAs(pm, () -> planRevisionService.diff(rev1, rev1));

        assertThat(d.rows()).allMatch(r -> r.changeKind() == ChangeKind.UNCHANGED);
    }

    @Test
    void aRevisionFromAnotherCaseCannotBeDiffedAgainst() {
        // againstRevisionId is a value taken from a query string, exactly like
        // revisionId itself -- it needs the same resolution obligation, and a
        // caller scoped widely enough to read two DIFFERENT cases' revisions must
        // still not be able to diff one case's schedule against an unrelated one's.
        assertThatThrownBy(() -> runAs(pm, () -> planRevisionService.diff(rev1, revisionOfAnotherCase)))
                .isInstanceOf(NoSuchElementException.class);
    }

    private PlanRevisionDiffRowView rowFor(PlanRevisionDiffView d, String milestoneName) {
        return d.rows().stream()
                .filter(r -> r.milestoneName().equals(milestoneName))
                .findFirst().orElseThrow();
    }

    private void saveRevision(UUID id, UUID forCaseId, int number, PlanRevisionStatus status) {
        PlanRevision revision = new PlanRevision();
        revision.setId(id);
        revision.setTenantId(tenant);
        revision.setCaseId(forCaseId);
        revision.setRevisionNumber(number);
        revision.setStatus(status);
        revision.setIssuedAt(Instant.now(clock));
        revision.setIssuedBy(pm);
        revision.setIssueNote("Diff fixture");
        revisions.save(revision);
    }

    private PlanRevisionItem itemRow(UUID planRevisionId, UUID forCaseId, Milestone m, String milestoneName,
                                      LocalDate dueDate, int sortOrder) {
        return new PlanRevisionItem(Uuid7.generate(), tenant, planRevisionId, forCaseId, m.getId(),
                m.getMilestoneDefinitionId(), "Delivery", milestoneName, dueDate, null, 2, true, sortOrder,
                Instant.now(clock));
    }

    /** Runs {@code action} as {@code user} in a fresh request scope and returns its result. */
    private <T> T runAs(UUID user, java.util.function.Supplier<T> action) {
        var result = new AtomicReference<T>();
        fixture.runAsUser(tenant, user, () -> result.set(action.get()));
        return result.get();
    }

    /**
     * A single-stage, three-portal-visible-milestone case owned by {@code
     * owningTeamId}. Neither shape approval nor {@link PlanShapeService} is
     * involved -- this test compares hand-seeded {@link PlanRevisionItem} rows
     * directly, never {@link PlanRevisionService#issue}, so gate 1 is irrelevant
     * here.
     */
    private CaseAndVersion openThreeMilestoneCase(UUID owningTeamId) {
        UUID templateId = workflows.createTemplate("Diff Fixture " + Uuid7.generate(), "").id();
        UUID draftId = workflows.createDraft(templateId);
        workflows.replaceDraft(draftId, new WorkflowDefinitionRequest(
                List.of(stage("s1", "Delivery", List.of(
                        milestone("m1", "Kickoff", 2, List.of(), List.of(manual("Sign up"))),
                        milestone("m2", "Internal staging", 1, List.of(), List.of(manual("Review"))),
                        milestone("m3", "Go live", 2, List.of(), List.of(manual("Launch")))))),
                List.of(), 0L));
        publishService.publish(draftId);

        UUID customerId = fixture.createCustomer(tenant, "Diff Customer " + Uuid7.generate(), null, null, owningTeamId);
        CaseView view = caseService.create(new CreateCaseRequest(customerId, templateId,
                "Diff Case " + Uuid7.generate(), Map.of()));
        return new CaseAndVersion(view.id(), draftId);
    }

    private record CaseAndVersion(UUID caseId, UUID versionId) {}

    private Milestone milestoneRow(UUID forCaseId, UUID versionId, String milestoneName) {
        UUID definitionId = milestoneDefinitions.findByVersionIdOrderByOrdinal(versionId).stream()
                .filter(d -> d.getName().equals(milestoneName))
                .findFirst().orElseThrow().getId();
        return milestones.findByCaseIdOrderById(forCaseId).stream()
                .filter(m -> m.getMilestoneDefinitionId().equals(definitionId))
                .findFirst().orElseThrow();
    }
}
