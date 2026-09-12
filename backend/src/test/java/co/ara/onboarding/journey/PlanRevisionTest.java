package co.ara.onboarding.journey;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.CloneTemplateRequest;
import co.ara.onboarding.workflow.CustomerTemplateService;
import co.ara.onboarding.workflow.DecidePlanRequest;
import co.ara.onboarding.workflow.MilestoneDefinitionRepository;
import co.ara.onboarding.workflow.PlanDecision;
import co.ara.onboarding.workflow.PlanGateException;
import co.ara.onboarding.workflow.PlanShapeApprovalStatus;
import co.ara.onboarding.workflow.PlanShapeService;
import co.ara.onboarding.workflow.PublishService;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WorkflowService;
import co.ara.onboarding.workflow.WorkflowVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;
import java.time.LocalDate;
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
 * Sub-project 3A, Task 24: {@link PlanRevisionService#issue} -- gate 2 of QA
 * Q22. Every fixture case here is a customer-tier clone (never a catalogue
 * template), because {@code issue}'s gate-1 ordering rule (a case's schedule
 * cannot be issued before its shape is approved -- {@link PlanShapeService}'s
 * gate 1 is customer-tier only) makes a catalogue-template case moot: it could
 * never have an approved shape to begin with.
 *
 * {@code pm} holds exactly {@code plan.issue} (TEAM), {@code workflow.view}
 * (ALL) and {@code milestone.edit} (TEAM) -- the first and third genuinely
 * TEAM-scoped (this suite's own "test at the narrowest catalogued scope"
 * exercise), the second forced to ALL because {@code workflow.view} is
 * ALL-only in the catalog. {@code workflow.view} is NOT there to satisfy
 * {@code PlanShapeService.currentApproval}'s gate -- it is there because
 * {@code issue} itself cannot read the pinned version's Stage/
 * MilestoneDefinition rows without it (see {@code
 * PlanRevisionService.stagesAndDefinitionsOf}'s own javadoc: neither entity has
 * a descriptor, so any OTHER permission key would crash the read, not merely
 * deny it). {@link #aPlanIssueOnlyActorCanReadTheShapeApprovalThroughTheWidenedGate}
 * isolates the {@code currentApproval} cross-module fix from that unrelated
 * requirement with a SEPARATE, even-narrower probe actor who never needs to
 * reach the Stage/MilestoneDefinition read at all.
 */
class PlanRevisionTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired PlanRevisionService planRevisionService;
    @Autowired MilestoneService milestoneService;
    @Autowired CaseService caseService;
    @Autowired PlanShapeService planShapeService;
    @Autowired WorkflowService workflows;
    @Autowired PublishService publishService;
    @Autowired CustomerTemplateService customerTemplates;
    @Autowired WorkflowVersionRepository versionRepository;
    @Autowired MilestoneDefinitionRepository milestoneDefinitions;
    @Autowired MilestoneRepository milestones;
    @Autowired RoleService roles;
    @Autowired CaseRepository caseRepository;
    @Autowired CaseEngine engine;

    private UUID tenant;
    private UUID team;
    private UUID pm;
    /**
     * Holds ONLY {@code plan.approve_schedule} at TEAM -- deliberately narrower
     * than the seeded Account Manager template (DEPARTMENT), which itself holds
     * no {@code case.hold} grant at all. This is the actor that proves {@code
     * CaseService.resume}'s widened gate (Task 25) actually works: without it,
     * {@code decide}'s own internal {@code resume} call would 403 on exactly
     * this actor, the same cross-module trap Task 24 already found and fixed
     * for {@code plan.issue}/{@code currentApproval}.
     */
    private UUID am;
    private UUID caseId;
    private UUID customerVersionId;
    private UUID kickoffId;

    @BeforeEach
    void seedApprovedCaseAndNarrowActor() {
        tenant = fixture.createTenant("plan-revision-" + Uuid7.generate());

        fixture.runAs(tenant, () -> {
            team = fixture.createTeam(tenant, "Delivery Team " + Uuid7.generate());

            pm = fixture.createUser(tenant, "pm+" + Uuid7.generate() + "@plan-revision.example");
            fixture.addToTeam(tenant, pm, team);
            UUID pmRole = roles.createRole("Narrow PM " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.PLAN_ISSUE, Scope.TEAM,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL,
                    PermissionKeys.MILESTONE_EDIT, Scope.TEAM));
            roles.assignRole(pm, pmRole);

            am = fixture.createUser(tenant, "am+" + Uuid7.generate() + "@plan-revision.example");
            fixture.addToTeam(tenant, am, team);
            // WORKFLOW_VIEW (ALL) is here for the same reason pm's role carries it above:
            // CaseService.toView (reached through resume()'s own return value) reads the
            // current Stage row under the hardcoded workflow.view key regardless of which
            // permission resolved the case itself -- the already-documented "viewing a
            // case's full representation is gated by more than case.view" gap (CLAUDE.md).
            // Confirmed empirically: this actor 404'd resolving the case's stage name
            // before this grant was added, never reaching the outcome under test.
            UUID amRole = roles.createRole("Narrow AM " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.PLAN_APPROVE_SCHEDULE, Scope.TEAM,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
            roles.assignRole(am, amRole);
        });

        caseId = openApprovedCustomerCase(team);
        kickoffId = milestoneIdFor(caseId, "Kickoff");
    }

    @Test
    void issuingSnapshotsEveryPortalVisibleMilestoneAsItStandsNow() {
        PlanRevisionView rev = runAs(pm, () -> planRevisionService.issue(caseId, new IssueRevisionRequest("v1")));

        assertThat(rev.revisionNumber()).isEqualTo(1);
        assertThat(rev.items()).extracting(PlanRevisionItemView::milestoneName)
                .containsExactly("Kickoff", "Go live");       // the internal-only one is absent
        assertThat(rev.items().get(0).dueDate()).isEqualTo(currentDueDateOf(caseId, "Kickoff"));
    }

    @Test
    void aLaterDateChangeDoesNotAlterAnIssuedRevision() {
        // The whole point of the snapshot. Without this, "what did we send?" reads
        // back today's dates and the record proves nothing.
        PlanRevisionView rev = runAs(pm, () -> planRevisionService.issue(caseId, note()));
        LocalDate captured = rev.items().get(0).dueDate();

        runAs(pm, () -> milestoneService.update(kickoffId, rescheduleTo(captured.plusDays(30))));

        PlanRevisionView reread = runAs(pm, () -> planRevisionService.get(rev.id()));
        assertThat(reread.items().get(0).dueDate()).isEqualTo(captured);
    }

    @Test
    void issuingASecondRevisionSupersedesTheOutstandingOne() {
        PlanRevisionView first = runAs(pm, () -> planRevisionService.issue(caseId, note()));
        PlanRevisionView second = runAs(pm, () -> planRevisionService.issue(caseId, note()));

        PlanRevisionView firstReread = runAs(pm, () -> planRevisionService.get(first.id()));
        assertThat(firstReread.status()).isEqualTo(PlanRevisionStatus.SUPERSEDED);
        assertThat(second.revisionNumber()).isEqualTo(2);
    }

    @Test
    void aRevisionCannotBeIssuedUntilTheShapeIsApproved() {
        // Gate 1 blocks nothing at runtime; it gets its teeth from THIS ordering
        // rule instead of a second hold (spec 5.3).
        UUID unapprovedCase = openCaseOnCustomerTemplateWithNoShapeApproval();

        assertThatThrownBy(() -> runAs(pm, () -> planRevisionService.issue(unapprovedCase, note())))
                .isInstanceOf(PlanGateException.class);        // 422
    }

    /**
     * Isolates the cross-module permission investigation from this class's own
     * javadoc: BEFORE {@code PlanShapeService.currentApproval}'s gate was
     * widened to also accept {@code plan.issue} (this task), a probe actor
     * holding ONLY {@code plan.issue} got AccessDeniedException calling it,
     * never the approval it was entitled to read. {@code pm} above cannot
     * demonstrate this either way -- it also holds {@code workflow.view} for
     * the unrelated Stage/MilestoneDefinition read {@code issue} itself needs,
     * and that alone already satisfies {@code currentApproval}'s gate. This
     * probe holds NOTHING but {@code plan.issue}, so it can only pass here
     * because of this task's fix, not by coincidence.
     */
    @Test
    void aPlanIssueOnlyActorCanReadTheShapeApprovalThroughTheWidenedGate() {
        var probeRef = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID user = fixture.createUser(tenant, "probe+" + Uuid7.generate() + "@plan-revision.example");
            UUID role = roles.createRole("Plan Issue Only " + Uuid7.generate(), "",
                    Map.of(PermissionKeys.PLAN_ISSUE, Scope.ALL));
            roles.assignRole(user, role);
            probeRef.set(user);
        });

        PlanShapeApprovalStatus status = runAs(probeRef.get(),
                () -> planShapeService.currentApproval(customerVersionId).orElseThrow().status());

        assertThat(status).isEqualTo(PlanShapeApprovalStatus.APPROVED);
    }

    /**
     * QA Q22/Q23 gate 2: the journey's FIRST-EVER schedule approval is what
     * releases the hold Task 26 (not yet built) will eventually put a
     * customer-template case into at creation. This test seeds that
     * precondition directly ({@link #openHeldCaseOnCustomerTemplate()}) rather
     * than waiting on Task 26's production wiring -- the plan's own pre-flight
     * ruling for this task.
     */
    @Test
    void theFirstApprovalReleasesTheHoldAndAccruesTheDaysWaited() {
        UUID heldCaseId = openHeldCaseOnCustomerTemplate();
        clock.advance(Duration.ofDays(3));

        PlanRevisionView rev = runAs(pm, () -> planRevisionService.issue(heldCaseId, note()));
        runAs(am, () -> planRevisionService.decide(rev.id(), approve()));

        CaseView c = caseView(heldCaseId);
        assertThat(c.status()).isEqualTo(CaseStatus.ACTIVE);
        assertThat(c.heldAt()).isNull();
        // Q8's SLA pause is correct for free because this goes through resume's own path.
        assertThat(c.totalHoldDays()).isGreaterThanOrEqualTo(1);
    }

    /**
     * Final whole-branch review finding #2: {@code CaseService.resume} used to
     * be gated {@code case.hold} OR {@code plan.approve_schedule}, widening
     * not just {@code decide}'s internal release call but the PUBLIC {@code
     * POST /cases/{id}/resume} endpoint too -- so {@code am} (holds only
     * {@code plan.approve_schedule}, deliberately never {@code case.hold},
     * the exact seeded Account Manager shape) could resume ANY case directly
     * over the API. Fixed by reverting {@code resume}'s own gate to {@code
     * case.hold} and giving {@code decide} a separate, ungated {@code
     * releasePlanHold} to call internally. This test proves the asymmetry:
     * the same {@code am} actor is refused calling the public method
     * directly, while {@link #theFirstApprovalReleasesTheHoldAndAccruesTheDaysWaited}
     * above already proves that actor still succeeds releasing the hold
     * through {@code decide}'s internal path.
     */
    @Test
    void anAccountManagerHoldingOnlyPlanApproveScheduleCannotResumeDirectlyThroughThePublicEndpoint() {
        UUID heldCaseId = openHeldCaseOnCustomerTemplate();

        assertThatThrownBy(() -> runAs(am, () -> caseService.resume(heldCaseId)))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * Q23: re-holding on every revision would mean an internal typo correction
     * freezes a live project until the customer replies again. Neither approving
     * nor rejecting a SECOND revision may disturb a case the first approval has
     * already made ACTIVE.
     */
    @Test
    void aLaterRevisionIsAdvisoryAndDoesNotReHoldTheJourney() {
        UUID heldCaseId = openHeldCaseOnCustomerTemplate();
        approveFirstRevision(heldCaseId);
        assertThat(caseView(heldCaseId).status()).isEqualTo(CaseStatus.ACTIVE);

        PlanRevisionView second = runAs(pm, () -> planRevisionService.issue(heldCaseId, note()));
        assertThat(caseView(heldCaseId).status()).isEqualTo(CaseStatus.ACTIVE);

        runAs(am, () -> planRevisionService.decide(second.id(), reject()));
        assertThat(caseView(heldCaseId).status()).isEqualTo(CaseStatus.ACTIVE);
    }

    /**
     * Issuing a second revision supersedes the first (Task 24's own behavior --
     * {@code plan_revision_one_outstanding_uq}), so deciding the now-SUPERSEDED
     * first revision must refuse rather than silently accept a stale decision.
     */
    @Test
    void decidingARevisionThatIsNotOutstandingIsRefused() {
        PlanRevisionView first = runAs(pm, () -> planRevisionService.issue(caseId, note()));
        runAs(pm, () -> planRevisionService.issue(caseId, note()));   // supersedes `first`

        assertThatThrownBy(() -> runAs(am, () -> planRevisionService.decide(first.id(), approve())))
                .isInstanceOf(PlanGateException.class);
    }

    /**
     * Final whole-branch review finding #4: {@code get}/{@code listForCase}/
     * {@code diff} were all gated {@code plan.issue} alone, but {@code
     * plan.issue} and {@code plan.approve_schedule} are deliberately seeded
     * to two DIFFERENT templates ({@code RoleTemplates}: Project Manager,
     * Account Manager) -- so the ONE seeded role that can actually decide a
     * schedule revision (Account Manager) got a 403 trying to even READ the
     * revision it is supposed to approve, and {@code PlanTab.tsx}'s
     * unconditional {@code usePlanRevisions(caseId)} call meant the whole tab
     * errored out for that actor. {@code am} holds ONLY {@code
     * plan.approve_schedule} (see this class's own field javadoc, no {@code
     * plan.issue} at all) -- this proves the widening lets it succeed at all
     * three reads.
     */
    @Test
    void anAccountManagerHoldingOnlyPlanApproveScheduleCanReadRevisions() {
        PlanRevisionView first = runAs(pm, () -> planRevisionService.issue(caseId, note()));
        PlanRevisionView second = runAs(pm, () -> planRevisionService.issue(caseId, note()));

        PlanRevisionView read = runAs(am, () -> planRevisionService.get(second.id()));
        assertThat(read.id()).isEqualTo(second.id());

        List<PlanRevisionView> list = runAs(am, () -> planRevisionService.listForCase(caseId));
        assertThat(list).extracting(PlanRevisionView::id).contains(first.id(), second.id());

        PlanRevisionDiffView diff = runAs(am, () -> planRevisionService.diff(second.id(), first.id()));
        assertThat(diff.rows()).isNotEmpty();
    }

    private DecidePlanRequest approve() {
        return new DecidePlanRequest(PlanDecision.APPROVED, "Approved", null);
    }

    private DecidePlanRequest reject() {
        return new DecidePlanRequest(PlanDecision.REJECTED, "Rejected", null);
    }

    /** Issues and approves a case's first revision, releasing its hold. */
    private void approveFirstRevision(UUID forCaseId) {
        PlanRevisionView first = runAs(pm, () -> planRevisionService.issue(forCaseId, note()));
        runAs(am, () -> planRevisionService.decide(first.id(), approve()));
    }

    /**
     * Same shape as {@link #openApprovedCustomerCase} (shape-approved
     * customer-tier clone, cased) but with the case additionally forced into
     * ON_HOLD directly against the repository -- Task 26, not yet built, is
     * what will eventually make case creation on a customer template start this
     * way in production; seeded directly here rather than waiting on that
     * wiring, per this task's own pre-flight ruling. Mirrors
     * {@code RequirementTest.satisfyingIsRefusedWhileTheCaseIsOnHold}'s own
     * "set the status directly, no production path yet" pattern.
     */
    private UUID openHeldCaseOnCustomerTemplate() {
        UUID heldCaseId = openApprovedCustomerCase(team);
        fixture.runAs(tenant, () -> {
            Case c = caseRepository.findById(heldCaseId).orElseThrow();
            c.setStatus(CaseStatus.ON_HOLD);
            c.setHeldAt(java.time.Instant.now(clock));
            caseRepository.saveAndFlush(c);
        });
        return heldCaseId;
    }

    /** Reads a case's current view as the tenant's fixture superuser. */
    private CaseView caseView(UUID forCaseId) {
        var result = new AtomicReference<CaseView>();
        fixture.runAs(tenant, () -> result.set(caseService.get(forCaseId)));
        return result.get();
    }

    /** Runs {@code action} as {@code user} in a fresh request scope and returns its result. */
    private <T> T runAs(UUID user, java.util.function.Supplier<T> action) {
        var result = new AtomicReference<T>();
        fixture.runAsUser(tenant, user, () -> result.set(action.get()));
        return result.get();
    }

    private IssueRevisionRequest note() {
        return new IssueRevisionRequest("Issued by fixture");
    }

    private UpdateMilestoneRequest rescheduleTo(LocalDate date) {
        return new UpdateMilestoneRequest(null, date);
    }

    /**
     * A customer-tier clone (Kickoff / Internal Review [not portal-visible] /
     * Go live, one stage), published, cased, shape-submitted and APPROVED.
     * Stores the clone's published version id in {@code customerVersionId} for
     * later tests that need it directly.
     *
     * Sub-project 3A Task 26 landed after this fixture was written: a case on
     * a customer-owned template now starts {@code ON_HOLD} pending its own
     * first schedule approval (QA Q22/Q23 gate 2), so {@code cloned.caseId()}
     * has no entered stage or due dates yet at this point -- the tests that
     * use this fixture are about {@code issue()}'s own snapshot mechanics
     * (due dates, revision numbering), not about the hold itself (that is
     * {@code PlanHoldTest}'s job now). Rather than consuming a real schedule
     * revision here to release the hold -- which would shift every {@code
     * revisionNumber()} assertion below by one -- the case is pushed straight
     * to ACTIVE directly against the repository and {@link CaseEngine},
     * mirroring {@link #openHeldCaseOnCustomerTemplate()}'s own
     * "seed the state directly, no production path needed for this test"
     * pattern, just in the opposite direction.
     */
    private UUID openApprovedCustomerCase(UUID owningTeamId) {
        var caseRef = new AtomicReference<ClonedCase>();
        fixture.runAs(tenant, () -> {
            ClonedCase cloned = publishedCustomerClone(owningTeamId);
            UUID contactId = fixture.createContact(tenant, cloned.customerId(),
                    "sponsor+" + Uuid7.generate() + "@plan-revision.example");
            planShapeService.submit(cloned.versionId());
            planShapeService.decide(cloned.versionId(),
                    new DecidePlanRequest(PlanDecision.APPROVED, "Approved", contactId));
            customerVersionId = cloned.versionId();

            Case c = caseRepository.findById(cloned.caseId()).orElseThrow();
            c.setStatus(CaseStatus.ACTIVE);
            c.setHeldAt(null);
            c = caseRepository.saveAndFlush(c);
            engine.reconcile(c);

            caseRef.set(cloned);
        });
        return caseRef.get().caseId();
    }

    /** Same shape as {@link #openApprovedCustomerCase}, minus the submit/decide. */
    private UUID openCaseOnCustomerTemplateWithNoShapeApproval() {
        var caseRef = new AtomicReference<ClonedCase>();
        fixture.runAs(tenant, () -> caseRef.set(publishedCustomerClone(team)));
        return caseRef.get().caseId();
    }

    /** Builds and publishes the clone, then opens a case against it. */
    private ClonedCase publishedCustomerClone(UUID owningTeamId) {
        UUID catalogueTemplateId = workflows.createTemplate("Fixture Onboarding " + Uuid7.generate(), "").id();
        UUID catalogueDraftId = workflows.createDraft(catalogueTemplateId);
        workflows.replaceDraft(catalogueDraftId, new WorkflowDefinitionRequest(
                List.of(stage("s1", "Delivery", List.of(
                        milestone("m1", "Kickoff", 2, List.of(), List.of(manual("Sign up"))),
                        milestone("m2", "Internal Review", 1, List.of(), List.of(manual("Review")), false),
                        milestone("m3", "Go live", 2, List.of(), List.of(manual("Launch")))))),
                List.of(), 0L));
        publishService.publish(catalogueDraftId);

        UUID customerId = fixture.createCustomer(tenant, "Plan Revision Customer " + Uuid7.generate(),
                null, null, owningTeamId);
        var clone = customerTemplates.clone(catalogueTemplateId,
                new CloneTemplateRequest(customerId, "Plan Revision Clone " + Uuid7.generate()));
        UUID cloneVersionId = versionRepository.findByTemplateIdOrderByVersionNoDesc(clone.id()).get(0).getId();
        publishService.publish(cloneVersionId);

        CaseView view = caseService.create(new CreateCaseRequest(customerId, clone.id(),
                "Plan Revision Case " + Uuid7.generate(), Map.of()));
        return new ClonedCase(view.id(), customerId, cloneVersionId);
    }

    private record ClonedCase(UUID caseId, UUID customerId, UUID versionId) {}

    private LocalDate currentDueDateOf(UUID forCaseId, String milestoneName) {
        var result = new AtomicReference<LocalDate>();
        fixture.runAs(tenant, () -> result.set(milestoneRow(forCaseId, milestoneName).getDueDate()));
        return result.get();
    }

    private UUID milestoneIdFor(UUID forCaseId, String milestoneName) {
        var result = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> result.set(milestoneRow(forCaseId, milestoneName).getId()));
        return result.get();
    }

    private Milestone milestoneRow(UUID forCaseId, String milestoneName) {
        UUID definitionId = milestoneDefinitions.findByVersionIdOrderByOrdinal(customerVersionId).stream()
                .filter(d -> d.getName().equals(milestoneName))
                .findFirst().orElseThrow().getId();
        return milestones.findByCaseIdOrderById(forCaseId).stream()
                .filter(m -> m.getMilestoneDefinitionId().equals(definitionId))
                .findFirst().orElseThrow();
    }
}
