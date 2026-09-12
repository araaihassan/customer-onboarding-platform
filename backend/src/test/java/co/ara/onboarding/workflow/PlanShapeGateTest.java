package co.ara.onboarding.workflow;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
 * Sub-project 3A, Task 20: {@link PlanShapeService#submit}/{@link
 * PlanShapeService#decide} -- gate 1 of QA Q22. {@link PlanShapeSchemaTest}
 * already proved the table's own shape (resubmission after rejection, the
 * frozen-version constraint that forced this into its own table); this class
 * proves the service layer built on top of it.
 *
 * {@code accountManager} holds BOTH {@code workflow.manage} (to submit) and
 * {@code plan.approve_shape} (to decide) -- both ALL-only in the catalog, so
 * there is no narrower scope to additionally test here, unlike a
 * DEPARTMENT/TEAM-catalogued permission would need.
 */
class PlanShapeGateTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired WorkflowService workflows;
    @Autowired PublishService publishService;
    @Autowired CustomerTemplateService customerTemplates;
    @Autowired PlanShapeService planShapeService;
    @Autowired RoleService roles;
    @Autowired WorkflowVersionRepository versionRepository;

    private UUID tenant;
    private UUID accountManager;
    private UUID draftVersionId;
    private UUID cataloguePublishedVersionId;
    private UUID customerVersionId;
    private UUID sponsorContactId;

    @BeforeEach
    void seedTemplatesAndVersions() {
        tenant = fixture.createTenant("plan-shape-gate-" + Uuid7.generate());

        fixture.runAs(tenant, () -> {
            accountManager = fixture.createUser(tenant, "account-manager+" + Uuid7.generate() + "@plan-shape-gate.example");
            UUID role = roles.createRole("Account Manager " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.WORKFLOW_MANAGE, Scope.ALL,
                    PermissionKeys.PLAN_APPROVE_SHAPE, Scope.ALL));
            roles.assignRole(accountManager, role);

            // A catalogue template (customerId null throughout): one published
            // version (cataloguePublishedVersionId) and a second, still-open
            // draft (draftVersionId) on the SAME template -- both refused by
            // submit(), for two different reasons.
            var catalogueTemplate = workflows.createTemplate("Standard Onboarding", "");
            UUID firstDraftId = workflows.createDraft(catalogueTemplate.id());
            workflows.replaceDraft(firstDraftId, new WorkflowDefinitionRequest(
                    List.of(stage("s1", "Stage One", List.of(
                            milestone("m1", "Kickoff", 2, List.of(), List.of(manual("Sign up")))))),
                    List.of(), 0L));
            publishService.publish(firstDraftId);
            cataloguePublishedVersionId = firstDraftId;
            draftVersionId = workflows.createDraft(catalogueTemplate.id());

            // A customer clone of it, published -- eligible for shape approval,
            // the shape submit()/decide() actually exercise.
            UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
            sponsorContactId = fixture.createContact(tenant, customerId, "sponsor+" + Uuid7.generate() + "@acme.example");
            var clone = customerTemplates.clone(catalogueTemplate.id(),
                    new CloneTemplateRequest(customerId, "Acme Onboarding"));
            UUID cloneDraftId = versionRepository.findByTemplateIdOrderByVersionNoDesc(clone.id()).get(0).getId();
            publishService.publish(cloneDraftId);
            customerVersionId = cloneDraftId;
        });
    }

    @Test
    void aDraftVersionCannotBeSubmittedForApproval() {
        // You cannot approve a shape that can still change, and a DRAFT can.
        assertThatThrownBy(() -> fixture.runAsUser(tenant, accountManager, () -> planShapeService.submit(draftVersionId)))
                .isInstanceOf(PlanGateException.class);       // 422
    }

    @Test
    void aCatalogueVersionCannotBeSubmittedForApproval() {
        // The whole two-gate story is defined at the customer tier.
        assertThatThrownBy(() -> fixture.runAsUser(tenant, accountManager, () -> planShapeService.submit(cataloguePublishedVersionId)))
                .isInstanceOf(PlanGateException.class);       // 422
    }

    @Test
    void decidingRecordsWhoPressedItAndWhoTheyPressedItFor() {
        fixture.runAsUser(tenant, accountManager, () -> planShapeService.submit(customerVersionId));
        fixture.runAsUser(tenant, accountManager, () -> planShapeService.decide(customerVersionId,
                new DecidePlanRequest(PlanDecision.APPROVED, "Approved by email 2026-09-09", sponsorContactId)));

        // Read back through accountManager -- the SAME narrow actor who just
        // submitted and decided, holding only WORKFLOW_MANAGE and
        // PLAN_APPROVE_SHAPE, never WORKFLOW_VIEW. Reading this back via the
        // fixture's full-authority administrator (as this test originally did)
        // would never have caught currentApproval being gated WORKFLOW_VIEW-only:
        // an actor who can submit and decide but not read back their own
        // decision is exactly the "tests that construct their own preconditions
        // converge on the happy scope" trap CLAUDE.md names elsewhere.
        var currentRef = new AtomicReference<PlanShapeApprovalView>();
        fixture.runAsUser(tenant, accountManager,
                () -> currentRef.set(planShapeService.currentApproval(customerVersionId).orElseThrow()));
        PlanShapeApprovalView current = currentRef.get();

        assertThat(current.status()).isEqualTo(PlanShapeApprovalStatus.APPROVED);
        assertThat(current.decidedBy()).isEqualTo(accountManager);       // sub-project 7 makes this the sponsor
        assertThat(current.decidedOnBehalfOf()).isEqualTo(sponsorContactId);
    }

    @Test
    void aDecisionIsOneShot() {
        fixture.runAsUser(tenant, accountManager, () -> planShapeService.submit(customerVersionId));
        fixture.runAsUser(tenant, accountManager, () -> planShapeService.decide(customerVersionId, approve()));
        assertThatThrownBy(() -> fixture.runAsUser(tenant, accountManager, () -> planShapeService.decide(customerVersionId, approve())))
                .isInstanceOf(PlanGateException.class);
    }

    /**
     * Final whole-branch review finding #5: plan_shape_approval had no
     * equivalent of plan_revision's "at most one outstanding" guard -- a
     * second submit while one row was still SUBMITTED silently stranded the
     * first (never resolved, never visible again, since currentRow always
     * picks the newest by submittedAt) and, absent the pre-check added
     * alongside V22's partial unique index, would have surfaced as a raw
     * DataIntegrityViolationException rather than a clear PlanGateException.
     * Unlike {@link #resubmittingAfterARejectionStartsANewApprovalAndTheLatestRowWins},
     * this never decides the first submission -- it is still outstanding when
     * the second submit is attempted.
     */
    @Test
    void aSecondSubmitWhileOneIsStillOutstandingIsRefused() {
        fixture.runAsUser(tenant, accountManager, () -> planShapeService.submit(customerVersionId));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, accountManager,
                () -> planShapeService.submit(customerVersionId)))
                .isInstanceOf(PlanGateException.class);       // 422, not a raw constraint violation

        // The original SUBMITTED row is untouched and still decidable -- proves
        // the refused second submit did not strand it or half-write anything.
        var currentRef = new AtomicReference<PlanShapeApprovalView>();
        fixture.runAsUser(tenant, accountManager,
                () -> currentRef.set(planShapeService.currentApproval(customerVersionId).orElseThrow()));
        assertThat(currentRef.get().status()).isEqualTo(PlanShapeApprovalStatus.SUBMITTED);

        fixture.runAsUser(tenant, accountManager, () -> planShapeService.decide(customerVersionId, approve()));
        fixture.runAsUser(tenant, accountManager,
                () -> currentRef.set(planShapeService.currentApproval(customerVersionId).orElseThrow()));
        assertThat(currentRef.get().status()).isEqualTo(PlanShapeApprovalStatus.APPROVED);
    }

    @Test
    void resubmittingAfterARejectionStartsANewApprovalAndTheLatestRowWins() {
        fixture.runAsUser(tenant, accountManager, () -> planShapeService.submit(customerVersionId));
        fixture.runAsUser(tenant, accountManager, () -> planShapeService.decide(customerVersionId, reject()));
        fixture.runAsUser(tenant, accountManager, () -> planShapeService.submit(customerVersionId));

        // Same narrow-actor read as above -- see that test's comment.
        var currentRef = new AtomicReference<PlanShapeApprovalView>();
        fixture.runAsUser(tenant, accountManager,
                () -> currentRef.set(planShapeService.currentApproval(customerVersionId).orElseThrow()));

        assertThat(currentRef.get().status()).isEqualTo(PlanShapeApprovalStatus.SUBMITTED);
    }

    @Test
    void aNarrowActorHoldingOnlySubmitAndDecidePermissionsCanReadBackTheCurrentApproval() {
        // The exact gap Task 20's review flagged: accountManager holds
        // WORKFLOW_MANAGE and PLAN_APPROVE_SHAPE only, never WORKFLOW_VIEW.
        // Before the fix, currentApproval was gated WORKFLOW_VIEW alone, so this
        // call 403'd even though accountManager is precisely the actor who just
        // submitted and decided this same version.
        fixture.runAsUser(tenant, accountManager, () -> planShapeService.submit(customerVersionId));

        var beforeDecision = new AtomicReference<PlanShapeApprovalView>();
        fixture.runAsUser(tenant, accountManager,
                () -> beforeDecision.set(planShapeService.currentApproval(customerVersionId).orElseThrow()));
        assertThat(beforeDecision.get().status()).isEqualTo(PlanShapeApprovalStatus.SUBMITTED);

        fixture.runAsUser(tenant, accountManager, () -> planShapeService.decide(customerVersionId, approve()));

        var afterDecision = new AtomicReference<PlanShapeApprovalView>();
        fixture.runAsUser(tenant, accountManager,
                () -> afterDecision.set(planShapeService.currentApproval(customerVersionId).orElseThrow()));
        assertThat(afterDecision.get().status()).isEqualTo(PlanShapeApprovalStatus.APPROVED);
    }

    private DecidePlanRequest approve() {
        return new DecidePlanRequest(PlanDecision.APPROVED, "Approved", sponsorContactId);
    }

    private DecidePlanRequest reject() {
        return new DecidePlanRequest(PlanDecision.REJECTED, "Needs rework", sponsorContactId);
    }
}
