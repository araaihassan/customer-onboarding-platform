package co.ara.onboarding.workflow;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Gate 1 of QA Q22: the customer's decision on a plan's SHAPE (stages,
 * milestones, requirements, estimated durations), once per {@link
 * WorkflowVersion} -- see {@link PlanShapeApproval}'s own javadoc for why this
 * is a separate, append-mostly table rather than columns on
 * {@code workflow_version} itself.
 *
 * Every read reaches {@link PlanShapeApproval} through {@link AuthorizedQuery},
 * even though {@code PlanShapeApprovalDescriptor} fails closed for every scope
 * but ALL (both {@code workflow.manage} and {@code plan.approve_shape} are
 * ALL-only in the catalog today) --
 * {@code AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly}
 * binds to every {@code *Service} in this package regardless of whether the
 * permission it authorizes happens to need per-record scoping.
 */
@Service
public class PlanShapeService {

    private final PlanShapeApprovalRepository approvals;
    private final WorkflowVersionRepository versions;
    private final WorkflowTemplateRepository templates;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final AuditRecorder audit;

    public PlanShapeService(PlanShapeApprovalRepository approvals,
                            WorkflowVersionRepository versions,
                            WorkflowTemplateRepository templates,
                            AuthorizedQuery authorizedQuery,
                            AuthContextProvider contextProvider,
                            AuditRecorder audit) {
        this.approvals = approvals;
        this.versions = versions;
        this.templates = templates;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.audit = audit;
    }

    /**
     * Refuses two ways, both {@link PlanGateException} (422):
     * <ul>
     *   <li>{@code versionId} is still DRAFT -- a shape that can still change
     *       cannot be submitted for approval;</li>
     *   <li>{@code versionId}'s owning template is a catalogue template
     *       ({@code customerId} null) -- QA Q22's whole two-gate approval story
     *       is defined at the customer tier, so a catalogue template's version
     *       is never submitted for shape approval at all.</li>
     * </ul>
     * Resolved through {@link AuthorizedQuery}, never a raw id: {@code
     * versionId} is a write-path argument taken straight from a URL, the exact
     * shape that produced three escalations in sub-project 1.
     */
    @RequirePermission(PermissionKeys.WORKFLOW_MANAGE)
    @Transactional
    public PlanShapeApprovalView submit(UUID versionId) {
        WorkflowVersion version = authorizedQuery.getById(
                versions, WorkflowVersion.class, PermissionKeys.WORKFLOW_MANAGE, versionId);
        if (version.getStatus() != VersionStatus.PUBLISHED) {
            throw new PlanGateException("Version " + versionId
                    + " is still a draft -- a shape that can still change cannot be submitted for approval");
        }

        WorkflowTemplate template = authorizedQuery.getById(
                templates, WorkflowTemplate.class, PermissionKeys.WORKFLOW_MANAGE, version.getTemplateId());
        if (template.getCustomerId() == null) {
            throw new PlanGateException("Template " + template.getId()
                    + " is a catalogue template -- shape approval applies only at the customer tier");
        }

        PlanShapeApproval approval = new PlanShapeApproval();
        approval.setId(Uuid7.generate());
        approval.setTenantId(TenantContext.getRequired());
        approval.setVersionId(versionId);
        approval.setTemplateId(template.getId());
        approval.setCustomerId(template.getCustomerId());
        approval.setStatus(PlanShapeApprovalStatus.SUBMITTED);
        approval.setSubmittedAt(Instant.now());
        approval.setSubmittedBy(contextProvider.principal().userId());
        approvals.save(approval);

        audit.record(AuditActions.PLAN_SHAPE_SUBMITTED, "plan_shape_approval", approval.getId(),
                "Submitted v" + version.getVersionNo() + " of " + template.getName() + " for shape approval",
                Map.of("versionId", versionId.toString(), "templateId", template.getId().toString()));

        return toView(approval);
    }

    /**
     * Refuses ({@link PlanGateException}, 422) when there is no currently-
     * SUBMITTED row for {@code versionId} -- a decision is one-shot, so
     * deciding an already-decided (or never-submitted) version is refused
     * rather than silently overwriting the prior decision. Resubmission after
     * a rejection ({@link #submit} again) is what starts a fresh, decidable
     * row -- see {@link PlanShapeApproval}'s own javadoc.
     *
     * {@code decidedBy} is always the actor who called THIS method -- today
     * always internal staff, since sub-project 7 is what eventually lets a
     * real customer sponsor press this button themselves. {@code
     * decidedOnBehalfOf} is a genuinely different id, from {@link
     * DecidePlanRequest#decidedOnBehalfOfContactId()}: the {@code
     * customer_contact} the internal actor is recording this decision on
     * behalf of.
     */
    @RequirePermission(PermissionKeys.PLAN_APPROVE_SHAPE)
    @Transactional
    public PlanShapeApprovalView decide(UUID versionId, DecidePlanRequest request) {
        PlanShapeApproval current = currentRow(versionId, PermissionKeys.PLAN_APPROVE_SHAPE)
                .filter(a -> a.getStatus() == PlanShapeApprovalStatus.SUBMITTED)
                .orElseThrow(() -> new PlanGateException(
                        "Version " + versionId + " has no submitted shape approval to decide"));

        current.setStatus(request.outcome() == PlanDecision.APPROVED
                ? PlanShapeApprovalStatus.APPROVED : PlanShapeApprovalStatus.REJECTED);
        current.setDecidedAt(Instant.now());
        current.setDecidedBy(contextProvider.principal().userId());
        current.setDecidedOnBehalfOf(request.decidedOnBehalfOfContactId());
        current.setDecisionNote(request.note());
        approvals.save(current);

        audit.record(AuditActions.PLAN_SHAPE_DECIDED, "plan_shape_approval", current.getId(),
                "Recorded a " + current.getStatus() + " shape decision on version " + versionId,
                Map.of("versionId", versionId.toString(), "outcome", request.outcome().name()));

        return toView(current);
    }

    /**
     * The latest row for {@code versionId}, by {@code submittedAt} -- "current
     * state" means the latest row, never an aggregate over every submission.
     * Sub-project 3A Task 24 (in the {@code journey} module -- {@code
     * journey -> workflow} already exists and is allowed) calls this directly
     * to enforce gate 2's ordering rule against the returned status.
     *
     * Gated {@code workflow.view}, not {@code plan.approve_shape}: this is a
     * read of the plan's state, the same tier {@link WorkflowService#getDefinition}
     * already reads the rest of a version's graph at, not the narrower
     * decision-making permission {@link #decide} needs.
     */
    @RequirePermission(PermissionKeys.WORKFLOW_VIEW)
    @Transactional(readOnly = true)
    public Optional<PlanShapeApprovalView> currentApproval(UUID versionId) {
        return currentRow(versionId, PermissionKeys.WORKFLOW_VIEW).map(this::toView);
    }

    /**
     * permissionKey is the caller's own gate, not always WORKFLOW_VIEW: {@link
     * #decide} (gated {@code plan.approve_shape}) must resolve its own current
     * row under that same permission, or a role holding {@code
     * plan.approve_shape} without {@code workflow.view} would pass decide's own
     * gate and then find nothing to decide -- the same "fetched with the
     * caller's own permission, not a different read one" rule {@link
     * WorkflowService#templateName} follows.
     */
    private Optional<PlanShapeApproval> currentRow(UUID versionId, String permissionKey) {
        return authorizedQuery.findAll(approvals, PlanShapeApproval.class, permissionKey,
                        (root, query, cb) -> cb.equal(root.get("versionId"), versionId),
                        PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "submittedAt")))
                .stream().findFirst();
    }

    private PlanShapeApprovalView toView(PlanShapeApproval a) {
        return new PlanShapeApprovalView(a.getId(), a.getVersionId(), a.getTemplateId(), a.getCustomerId(),
                a.getStatus(), a.getSubmittedAt(), a.getSubmittedBy(), a.getDecidedAt(), a.getDecidedBy(),
                a.getDecidedOnBehalfOf(), a.getDecisionNote());
    }
}
