package co.ara.onboarding.workflow;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizationService;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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
    private final StageRepository stages;
    private final MilestoneDefinitionRepository milestoneDefinitions;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final AuthorizationService authorization;
    private final AuditRecorder audit;

    public PlanShapeService(PlanShapeApprovalRepository approvals,
                            WorkflowVersionRepository versions,
                            WorkflowTemplateRepository templates,
                            StageRepository stages,
                            MilestoneDefinitionRepository milestoneDefinitions,
                            AuthorizedQuery authorizedQuery,
                            AuthContextProvider contextProvider,
                            AuthorizationService authorization,
                            AuditRecorder audit) {
        this.approvals = approvals;
        this.versions = versions;
        this.templates = templates;
        this.stages = stages;
        this.milestoneDefinitions = milestoneDefinitions;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.authorization = authorization;
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
     * Gated on all four of {@code workflow.view}, {@code workflow.manage},
     * {@code plan.approve_shape} and {@code plan.issue} (OR, via {@link
     * RequirePermission}'s array form) -- not {@code workflow.view} alone.
     * Task 20's review found that an actor holding exactly what {@link #submit}
     * and {@link #decide} require (workflow.manage, plan.approve_shape) but not
     * workflow.view got a hard 403 reading back the very approval they had just
     * submitted and decided, because {@code @RequirePermission} is coarse and
     * all-or-nothing: it cannot tell "the caller who can act on this" from "the
     * caller who can merely view it".
     *
     * {@code plan.issue} joined the set at sub-project 3A Task 24: {@code
     * journey.PlanRevisionService.issue} calls this method directly to enforce
     * gate 2's ordering rule (a schedule cannot be issued before its shape is
     * approved), and is gated {@code plan.issue} alone -- a role holding it
     * without also holding one of the original three would otherwise 403 on
     * this internal call rather than receive the {@code PlanGateException} an
     * unapproved shape should produce. Investigated empirically, not assumed:
     * the seeded Project Manager template happens to ALSO hold
     * {@code workflow.view} at ALL, so this would have "worked" for that one
     * template regardless -- a hand-built actor holding ONLY {@code plan.issue}
     * is what actually proves the gap (see journey.PlanRevisionTest).
     *
     * Every one of the four is ALL-only in the catalog today ({@link
     * PlanShapeApprovalDescriptor} fails closed for anything else) EXCEPT
     * {@code plan.issue}, which is RECORD-scoped on {@code onboarding_case} --
     * but {@link #currentRow} resolves {@code plan_shape_approval} rows, which
     * carry no case at all, so {@code plan.issue}'s own descriptor is never
     * consulted here: {@link #callersOwnReadPermission} only ever needs to know
     * WHICH key the caller holds, and {@code PlanShapeApprovalDescriptor}
     * fails closed (throws, in effect, by never matching) for any scope other
     * than ALL regardless of which of the four keys resolved it -- so a
     * TEAM-scoped {@code plan.issue} holder calling this method would find
     * nothing, the same "no descriptor for anything but ALL" shape {@link
     * PlanShapeApprovalDescriptor} already documents for the original three.
     */
    @RequirePermission({PermissionKeys.WORKFLOW_VIEW, PermissionKeys.WORKFLOW_MANAGE,
            PermissionKeys.PLAN_APPROVE_SHAPE, PermissionKeys.PLAN_ISSUE})
    @Transactional(readOnly = true)
    public Optional<PlanShapeApprovalView> currentApproval(UUID versionId) {
        return currentRow(versionId, callersOwnReadPermission()).map(this::toView);
    }

    /**
     * The customer-approved artifact for gate 1 (QA Q22): the current shape approval
     * state and a filtered rendering of {@code versionId}'s graph, in ONE response --
     * see {@link PlanShapeView}'s own javadoc for why they travel together.
     *
     * The rendering filters top-down: a stage whose own {@link Stage#isPortalVisible()}
     * is {@code false} is dropped ENTIRELY, milestones included, even one that is
     * itself {@code portalVisible} -- a milestone the customer cannot reach through its
     * own stage must not appear in the artifact they are asked to approve. Only a
     * surviving stage's milestones are then filtered by their own flag.
     *
     * Gated on {@code workflow.view} alone, unlike {@link #currentApproval}'s three
     * OR'd keys: reading the rendering is a plain view concern with no "the actor who
     * just acted on it" gap to bridge, since nothing here requires {@code
     * workflow.manage} or {@code plan.approve_shape} to reach.
     */
    @RequirePermission(PermissionKeys.WORKFLOW_VIEW)
    @Transactional(readOnly = true)
    public PlanShapeView render(UUID versionId) {
        // Resolved through AuthorizedQuery, never a raw id -- versionId is a write-path-
        // shaped argument taken straight from a URL (CLAUDE.md's standing rule), even
        // though this method only reads.
        authorizedQuery.getById(versions, WorkflowVersion.class, PermissionKeys.WORKFLOW_VIEW, versionId);

        List<Stage> stageEntities = authorizedQuery.findAll(stages, Stage.class, PermissionKeys.WORKFLOW_VIEW,
                        (root, query, cb) -> cb.equal(root.get("versionId"), versionId),
                        Pageable.unpaged(Sort.by("ordinal")))
                .getContent();
        List<MilestoneDefinition> milestoneEntities = authorizedQuery.findAll(
                        milestoneDefinitions, MilestoneDefinition.class, PermissionKeys.WORKFLOW_VIEW,
                        (root, query, cb) -> cb.equal(root.get("versionId"), versionId),
                        Pageable.unpaged(Sort.by("ordinal")))
                .getContent();

        Map<UUID, List<MilestoneDefinition>> milestonesByStage = new LinkedHashMap<>();
        for (MilestoneDefinition m : milestoneEntities) {
            milestonesByStage.computeIfAbsent(m.getStageId(), k -> new ArrayList<>()).add(m);
        }

        List<PlanShapeStageView> stageViews = stageEntities.stream()
                .filter(Stage::isPortalVisible)
                .map(s -> toStageView(s, milestonesByStage.getOrDefault(s.getId(), List.of())))
                .toList();

        PlanShapeApprovalView approval = currentApproval(versionId).orElse(null);
        return new PlanShapeView(approval, stageViews);
    }

    private PlanShapeStageView toStageView(Stage s, List<MilestoneDefinition> milestones) {
        List<PlanShapeMilestoneView> milestoneViews = milestones.stream()
                .filter(MilestoneDefinition::isPortalVisible)
                .map(this::toMilestoneView)
                .toList();
        return new PlanShapeStageView(s.getId(), s.getName(), milestoneViews);
    }

    private PlanShapeMilestoneView toMilestoneView(MilestoneDefinition m) {
        return new PlanShapeMilestoneView(m.getId(), m.getName(), m.getDescription(), m.getEstimatedDurationDays());
    }

    /**
     * Which of {@link #currentApproval}'s four OR'd keys THIS caller actually
     * holds, so the read below resolves scope under a permission the actor is
     * known to have -- {@link AuthorizedQuery} denies (empty result, not an
     * exception) when asked to scope by a key the caller does not hold at all,
     * rather than by one it holds too narrowly. The method's own gate has
     * already established at least one of the four holds; this only chooses
     * which.
     *
     * Order is NOT arbitrary any more, unlike before {@code plan.issue} joined
     * the set: the first three are ALL-only in the catalog, so whichever of
     * them resolves the caller always reaches {@code PlanShapeApprovalDescriptor}'s
     * ALL-scope short-circuit -- but {@code plan.issue} is RECORD-scoped, and
     * that same descriptor fails closed (disjunction) for anything narrower
     * than ALL. {@code plan.issue} is listed last precisely so a caller who
     * ALSO holds one of the first three (every seeded template that holds
     * plan.issue today, Project Manager included, also holds workflow.view at
     * ALL) resolves through that instead, and only a hand-built actor holding
     * plan.issue at ALL with none of the other three would ever actually reach
     * it here.
     */
    private String callersOwnReadPermission() {
        return CURRENT_APPROVAL_KEYS.stream()
                .filter(authorization::has)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "PermissionGateAspect should already have refused this call"));
    }

    private static final List<String> CURRENT_APPROVAL_KEYS = List.of(
            PermissionKeys.WORKFLOW_VIEW, PermissionKeys.WORKFLOW_MANAGE,
            PermissionKeys.PLAN_APPROVE_SHAPE, PermissionKeys.PLAN_ISSUE);

    /**
     * permissionKey is the caller's own gate, not always WORKFLOW_VIEW: {@link
     * #decide} (gated {@code plan.approve_shape}) must resolve its own current
     * row under that same permission, or a role holding {@code
     * plan.approve_shape} without {@code workflow.view} would pass decide's own
     * gate and then find nothing to decide -- the same "fetched with the
     * caller's own permission, not a different read one" rule {@link
     * WorkflowService#templateName} follows. {@link #currentApproval} follows
     * it too, via {@link #callersOwnReadPermission()}.
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
