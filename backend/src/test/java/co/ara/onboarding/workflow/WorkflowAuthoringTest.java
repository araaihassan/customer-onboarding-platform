package co.ara.onboarding.workflow;

import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.AttributeRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionView.StageView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static co.ara.onboarding.workflow.WorkflowFixtures.document;
import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.oneStage;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static co.ara.onboarding.workflow.WorkflowFixtures.twoStages;
import static co.ara.onboarding.workflow.WorkflowFixtures.withBranch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowAuthoringTest extends PostgresTestBase {

    @Autowired WorkflowService workflows;
    @Autowired TenantFixture fixture;
    @Autowired RoleService roles;
    @Autowired AuditEventRepository auditEvents;

    @Test
    void creatingATemplateCreatesAnEmptyDraft() {
        UUID tenant = fixture.createTenant("author-create");
        fixture.runAs(tenant, () -> {
            var view = workflows.createTemplate("Standard Enterprise", "The default");
            assertThat(view.status()).isEqualTo(TemplateStatus.ACTIVE);
            assertThat(view.currentVersionNo()).isNull();       // nothing published yet

            UUID draftId = workflows.createDraft(view.id());
            var definition = workflows.getDefinition(draftId);
            assertThat(definition.status()).isEqualTo(VersionStatus.DRAFT);
            assertThat(definition.versionNo()).isEqualTo(1);
            assertThat(definition.stages()).isEmpty();
        });
    }

    /**
     * createDraft's empty-draft branch never calls replaceDraft (see the comment on
     * that branch), so without its own audit call this path would create a draft
     * with no record that it happened at all -- the only sub-project-2 write so far
     * that would otherwise be silently unaudited.
     */
    @Test
    void creatingAnEmptyDraftWritesAnAuditEvent() {
        UUID tenant = fixture.createTenant("author-audit");
        var draftId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            var template = workflows.createTemplate("Audited", "");
            draftId.set(workflows.createDraft(template.id()));
        });

        fixture.runAs(tenant, () ->
            assertThat(auditEvents.findAll())
                    .extracting(e -> e.getAction() + ":" + e.getResourceId())
                    .contains("workflow.draft_saved:" + draftId.get()));
    }

    /**
     * The whole-document replace, including the two things per-element endpoints get
     * wrong: a branch rule targeting a stage that has no id yet, and a dependency
     * between two milestones added in the same request.
     */
    @Test
    void replacingADraftWritesTheWholeGraphAndResolvesKeys() {
        UUID tenant = fixture.createTenant("author-replace");
        fixture.runAs(tenant, () -> {
            var template = workflows.createTemplate("Keys", "");
            UUID draftId = workflows.createDraft(template.id());

            var request = new WorkflowDefinitionRequest(
                List.of(
                    stage("reg", "Registration", List.of(
                        milestone("company", "Company details", 2, List.of(),
                                  List.of(manual("Capture legal name"))),
                        milestone("kyc", "KYC pack", 3, List.of("company"),
                                  List.of(document("KYC bundle", "kyc"))))),
                    stage("legal", "Legal Review", List.of(
                        milestone("nda", "NDA signed", 5, List.of(), List.of(manual("Countersign"))))),
                    stage("live", "Go Live", List.of(
                        milestone("switch", "Switch on", 1, List.of(), List.of(manual("Flip"))))))
                .stream().toList(),
                List.of(new AttributeRequest("segment", "Segment", AttributeType.ENUM, true,
                        List.of("ENTERPRISE", "SMB"))),
                0L);

            // Registration branches to Go Live for SMB, skipping Legal Review.
            request = withBranch(request, "reg", "segment", "SMB", "live");

            var saved = workflows.replaceDraft(draftId, request);

            assertThat(saved.stages()).extracting(StageView::name)
                    .containsExactly("Registration", "Legal Review", "Go Live");
            UUID goLiveId = saved.stages().get(2).id();
            assertThat(saved.stages().get(0).branchRules().get(0).targetStageId())
                    .isEqualTo(goLiveId);

            var registration = saved.stages().get(0);
            UUID companyId = registration.milestones().get(0).id();
            assertThat(registration.milestones().get(1).dependsOnMilestoneIds())
                    .containsExactly(companyId);
        });
    }

    @Test
    void replacingADraftRemovesStagesTheRequestOmits() {
        UUID tenant = fixture.createTenant("author-remove");
        fixture.runAs(tenant, () -> {
            var template = workflows.createTemplate("Shrink", "");
            UUID draftId = workflows.createDraft(template.id());
            workflows.replaceDraft(draftId, twoStages());

            var one = workflows.replaceDraft(draftId, oneStage(workflows.getDefinition(draftId)));
            assertThat(one.stages()).hasSize(1);
        });
    }

    /**
     * Two administrators on one draft is the normal case in a small tenant, and the
     * loser must be told rather than silently overwritten -- a whole-graph PUT
     * discards the other's entire set of stages, not one field.
     *
     * assertThatThrownBy wraps the whole runAs call, not a nested call inside it: the
     * brief's original shape asserted inside the lambda, and since replaceDraft's
     * OptimisticLockingFailureException marks the (shared, still-open) runAs
     * transaction rollback-only, catching it inside the lambda leaves that mark in
     * place for the block's own commit and surfaces UnexpectedRollbackException
     * instead -- CLAUDE.md's "never assert an exception inside a fixture.runAs(...)
     * lambda" pitfall, hit here rather than merely cited. Fixed to match
     * aSecondDraftIsRefusedWhileOneIsOpen's (correct) shape below.
     */
    @Test
    void aStaleLockVersionIsRejected() {
        UUID tenant = fixture.createTenant("author-stale");
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            var template = workflows.createTemplate("Race", "");
            UUID draftId = workflows.createDraft(template.id());
            var first = workflows.replaceDraft(draftId, twoStages());

            var stale = new WorkflowDefinitionRequest(
                    twoStages().stages(), twoStages().attributes(), first.lockVersion() - 1);

            workflows.replaceDraft(draftId, stale);
        })).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void authoringRequiresWorkflowManage() {
        UUID tenant = fixture.createTenant("author-gate");
        var reader = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            reader.set(fixture.createUser(tenant, "reader@example.com"));
            grantRole(tenant, reader.get(), Map.of(PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, reader.get(),
                () -> workflows.createTemplate("Sneaky", "")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aSecondDraftIsRefusedWhileOneIsOpen() {
        UUID tenant = fixture.createTenant("author-two-drafts");
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            var template = workflows.createTemplate("Once", "");
            workflows.createDraft(template.id());
            workflows.createDraft(template.id());
        })).isInstanceOf(DraftAlreadyExistsException.class);
    }

    /**
     * The rejection names the open draft's versionId, not just the templateId --
     * without it, a caller has no way to resume or discard the draft that is
     * blocking it, which is exactly what left an abandoned draft unrecoverable
     * from the product UI.
     */
    @Test
    void theRejectionNamesTheOpenDraftsVersionId() {
        UUID tenant = fixture.createTenant("author-two-drafts-named");
        var openDraftId = new AtomicReference<UUID>();
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            var template = workflows.createTemplate("Once", "");
            openDraftId.set(workflows.createDraft(template.id()));
            workflows.createDraft(template.id());
        })).isInstanceOf(DraftAlreadyExistsException.class)
           .satisfies(e -> assertThat(((DraftAlreadyExistsException) e).versionId())
                   .isEqualTo(openDraftId.get()));
    }

    /** No such fixture helper exists yet; local exactly as the equivalent in DelegationGuardTest. */
    private void grantRole(UUID tenantId, UUID userId, Map<String, Scope> grants) {
        UUID roleId = roles.createRole("Grant-" + userId, "", grants);
        roles.assignRole(userId, roleId);
    }
}
