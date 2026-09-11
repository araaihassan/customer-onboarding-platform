package co.ara.onboarding.journey;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import co.ara.onboarding.workflow.MilestoneDefinition;
import co.ara.onboarding.workflow.MilestoneDefinitionRepository;
import co.ara.onboarding.workflow.PlanDecision;
import co.ara.onboarding.workflow.PlanGateException;
import co.ara.onboarding.workflow.DecidePlanRequest;
import co.ara.onboarding.workflow.PlanShapeApprovalStatus;
import co.ara.onboarding.workflow.PlanShapeService;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

/**
 * Gate 2 of QA Q22: the customer's decision on a CASE's schedule (calendar
 * dates, named owners), issued as a dated, immutable snapshot of every
 * portal-visible milestone as it stands right now -- see {@link PlanRevision}/
 * {@link PlanRevisionItem}'s own javadoc for why the items table is
 * append-only.
 *
 * <h2>Gate 1 -> gate 2 ordering (spec 5.3)</h2>
 * Gate 1 (the shape, {@link PlanShapeService}) blocks nothing on its own --
 * {@code submit}/{@code decide} are free-standing actions with no effect on
 * whether a case can be worked. It gets its teeth here instead: {@link #issue}
 * refuses ({@link PlanGateException}, 422) unless the case's PINNED VERSION
 * currently has an APPROVED shape. Checked through {@link
 * PlanShapeService#currentApproval} -- cross-module, {@code journey ->
 * workflow} already exists and is allowed -- and checked BEFORE any read of
 * the version's own Stage/MilestoneDefinition rows below, so an unapproved
 * case is refused without the caller ever needing to reach the permission this
 * class's second finding is about.
 *
 * <h2>The cross-module permission investigation (Task 24)</h2>
 * {@code currentApproval} was gated {@code workflow.view}/{@code
 * workflow.manage}/{@code plan.approve_shape} (Task 20) -- none of which
 * {@code issue} itself requires ({@code plan.issue} is a fourth, distinct
 * key). Investigated empirically with a hand-built actor holding ONLY {@code
 * plan.issue}: calling {@code issue} on an APPROVED case 403'd on the internal
 * {@code currentApproval} call, never reaching the {@link PlanGateException}
 * path -- the exact trap Task 20's own review found and fixed for a different
 * caller shape. Fixed the same way: {@code currentApproval}'s gate now also
 * accepts {@code plan.issue} (see that method's own javadoc for why {@code
 * plan.issue} is listed last in the OR, and why order stopped being arbitrary
 * once a RECORD-scoped key joined three ALL-only ones).
 *
 * Note this fix does NOT by itself make a plan.issue-only actor able to
 * complete {@code issue} end to end: reading the pinned version's Stage/
 * MilestoneDefinition rows below needs {@code workflow.view} (or {@code
 * workflow.manage}/{@code plan.approve_shape}) regardless, for a second,
 * unrelated, structural reason -- see {@link #stagesAndDefinitionsOf}'s own
 * javadoc. The seeded Project Manager template already holds {@code
 * workflow.view} at ALL alongside {@code plan.issue} at TEAM, so in practice
 * every template capable of calling this method today satisfies both
 * requirements already; {@code journey.PlanRevisionTest} proves the {@code
 * currentApproval} fix in isolation with a plan.issue-only probe actor that
 * never reaches the Stage/MilestoneDefinition read, rather than relying on
 * that coincidence to hide whether the fix was needed.
 */
@Service
public class PlanRevisionService {

    private final PlanRevisionRepository revisions;
    private final PlanRevisionItemRepository items;
    private final CaseRepository cases;
    private final MilestoneRepository milestones;
    private final StageRepository stages;
    private final MilestoneDefinitionRepository milestoneDefinitions;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final AuditRecorder audit;
    private final PlanShapeService planShapeService;
    private final CaseService caseService;
    private final Clock clock;

    public PlanRevisionService(PlanRevisionRepository revisions, PlanRevisionItemRepository items,
                               CaseRepository cases, MilestoneRepository milestones, StageRepository stages,
                               MilestoneDefinitionRepository milestoneDefinitions, AuthorizedQuery authorizedQuery,
                               AuthContextProvider contextProvider, AuditRecorder audit,
                               PlanShapeService planShapeService, CaseService caseService, Clock clock) {
        this.revisions = revisions;
        this.items = items;
        this.cases = cases;
        this.milestones = milestones;
        this.stages = stages;
        this.milestoneDefinitions = milestoneDefinitions;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.audit = audit;
        this.planShapeService = planShapeService;
        this.caseService = caseService;
        this.clock = clock;
    }

    /**
     * {@code caseId} is resolved through {@link AuthorizedQuery} under {@code
     * plan.issue} itself -- never {@code case.view}/{@code case.edit} -- the
     * same "fetched under the caller's own gating key" rule {@code
     * TaskService.create}/{@code CommentService.create} already follow, since
     * {@code plan.issue} is RECORD-scoped on {@code onboarding_case} (Task 23)
     * and needs no other permission to resolve it.
     */
    @RequirePermission(PermissionKeys.PLAN_ISSUE)
    @Transactional
    public PlanRevisionView issue(UUID caseId, IssueRevisionRequest request) {
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.PLAN_ISSUE, caseId);

        // Gate 1 -> gate 2 ordering, checked BEFORE any Stage/MilestoneDefinition
        // read below -- an unapproved case is refused without ever needing the
        // workflow.view-shaped permission that read requires.
        boolean shapeApproved = planShapeService.currentApproval(c.getVersionId())
                .filter(a -> a.status() == PlanShapeApprovalStatus.APPROVED)
                .isPresent();
        if (!shapeApproved) {
            throw new PlanGateException("Case " + caseId
                    + " cannot be issued a schedule revision until its plan's shape has been approved");
        }

        List<PlanRevision> existingForCase = authorizedQuery.findAll(revisions, PlanRevision.class,
                        PermissionKeys.PLAN_ISSUE,
                        (root, query, cb) -> cb.equal(root.get("caseId"), caseId),
                        Pageable.unpaged(Sort.by(Sort.Direction.DESC, "revisionNumber")))
                .getContent();
        int nextRevisionNumber = existingForCase.isEmpty() ? 1 : existingForCase.get(0).getRevisionNumber() + 1;

        // Supersede BEFORE inserting the new row, in the same transaction, so
        // plan_revision_one_outstanding_uq never sees two ISSUED rows for this
        // case at once. At most one existing row can be ISSUED (that index's own
        // guarantee), so there is at most one to supersede.
        //
        // saveAndFlush, not save: Hibernate's default flush ordering runs every
        // pending INSERT before any pending UPDATE, regardless of the order the
        // application code called save() in -- so a plain save() here would still
        // let the new revision's INSERT reach Postgres before this UPDATE, and
        // the partial unique index would see two ISSUED rows at once and refuse
        // the insert with a real DataIntegrityViolationException (proven red:
        // the very first run of issuingASecondRevisionSupersedesTheOutstandingOne
        // failed on "duplicate key value violates unique constraint
        // plan_revision_one_outstanding_uq" before this fix). Flushing here
        // forces the UPDATE to commit to the session immediately, strictly
        // before the new row is even constructed below.
        existingForCase.stream()
                .filter(r -> r.getStatus() == PlanRevisionStatus.ISSUED)
                .findFirst()
                .ifPresent(r -> {
                    r.setStatus(PlanRevisionStatus.SUPERSEDED);
                    revisions.saveAndFlush(r);
                });

        Instant now = Instant.now(clock);
        PlanRevision revision = new PlanRevision();
        revision.setId(Uuid7.generate());
        revision.setTenantId(TenantContext.getRequired());
        revision.setCaseId(caseId);
        revision.setRevisionNumber(nextRevisionNumber);
        revision.setStatus(PlanRevisionStatus.ISSUED);
        revision.setIssuedAt(now);
        revision.setIssuedBy(contextProvider.principal().userId());
        revision.setIssueNote(request.note());
        revisions.save(revision);

        List<PlanRevisionItem> itemRows = snapshotPortalVisibleMilestones(c, revision, now);
        items.saveAll(itemRows);

        audit.record(AuditActions.PLAN_REVISION_ISSUED, "onboarding_case", caseId,
                "Issued schedule revision " + nextRevisionNumber + " for case " + caseId,
                Map.of("planRevisionId", revision.getId().toString(),
                        "revisionNumber", String.valueOf(nextRevisionNumber)));

        return toView(revision, itemRows);
    }

    @RequirePermission(PermissionKeys.PLAN_ISSUE)
    @Transactional(readOnly = true)
    public PlanRevisionView get(UUID revisionId) {
        PlanRevision revision = authorizedQuery.getById(
                revisions, PlanRevision.class, PermissionKeys.PLAN_ISSUE, revisionId);
        List<PlanRevisionItem> itemRows = authorizedQuery.findAll(items, PlanRevisionItem.class,
                        PermissionKeys.PLAN_ISSUE,
                        (root, query, cb) -> cb.equal(root.get("planRevisionId"), revisionId),
                        Pageable.unpaged(Sort.by("sortOrder")))
                .getContent();
        return toView(revision, itemRows);
    }

    /**
     * Sub-project 3A, Task 27.5 (plan amendment): every schedule revision ever
     * issued for {@code caseId}, newest first -- the plural read {@code
     * usePlanRevisions(caseId)} (Task 28) needs and {@link #get} alone cannot
     * supply. {@code caseId} is resolved through {@link AuthorizedQuery} under
     * {@code plan.issue} itself, never {@code case.view}/{@code case.edit} --
     * the exact same "fetched under the caller's own gating key" rule {@link
     * #issue} already follows, so a plain case reader without {@code
     * plan.issue} cannot enumerate every schedule ever proposed for a case
     * they can otherwise see.
     *
     * Follows {@code CaseService.hasAnyApprovedRevision}/{@code
     * ApprovalService.listForCase}'s own shape: confirm the case itself is
     * visible first (a 404 on an out-of-scope {@code caseId}, not a silently
     * empty list), then read the child rows through the same {@code
     * JpaSpecificationExecutor}-shaped {@link PlanRevisionRepository} filtered
     * on {@code caseId} -- no hand-rolled JPQL, no new repository method.
     */
    @RequirePermission(PermissionKeys.PLAN_ISSUE)
    @Transactional(readOnly = true)
    public List<PlanRevisionView> listForCase(UUID caseId) {
        authorizedQuery.getById(cases, Case.class, PermissionKeys.PLAN_ISSUE, caseId);

        List<PlanRevision> revisionRows = authorizedQuery.findAll(revisions, PlanRevision.class,
                        PermissionKeys.PLAN_ISSUE,
                        (root, query, cb) -> cb.equal(root.get("caseId"), caseId),
                        Pageable.unpaged(Sort.by(Sort.Direction.DESC, "revisionNumber")))
                .getContent();

        Map<UUID, List<PlanRevisionItem>> itemsByRevisionId = authorizedQuery.findAll(items, PlanRevisionItem.class,
                        PermissionKeys.PLAN_ISSUE,
                        (root, query, cb) -> cb.equal(root.get("caseId"), caseId),
                        Pageable.unpaged(Sort.by("sortOrder")))
                .getContent().stream()
                .collect(groupingBy(PlanRevisionItem::getPlanRevisionId));

        return revisionRows.stream()
                .map(r -> toView(r, itemsByRevisionId.getOrDefault(r.getId(), List.of())))
                .toList();
    }

    /**
     * Compares {@code revisionId} (the "current" snapshot) against {@code
     * againstRevisionId} (the "previous" one), row by row, matched by {@code
     * milestoneDefinitionId} -- see {@link ChangeKind}'s own javadoc for why not
     * {@code milestoneName} or the runtime {@code milestoneId}. Computed
     * server-side, once, so two clients reading the same pair of revisions
     * cannot disagree about what changed (this class's own javadoc).
     *
     * Both ids are resolved through {@link AuthorizedQuery} under the caller's
     * own {@code plan.issue} grant -- {@code againstRevisionId} is as much a
     * value taken from a query string as {@code revisionId} itself, and needs
     * the same resolution obligation {@link #issue} names. Resolving both is not
     * enough on its own: a caller scoped widely enough to read two DIFFERENT
     * cases' revisions could otherwise diff one case's schedule against an
     * unrelated one's, so this also confirms both belong to the SAME case
     * before reading either's items -- refusing with {@link
     * NoSuchElementException} (404) exactly as an out-of-scope id does, rather
     * than a 403 that would confirm the other case's revision exists.
     */
    @RequirePermission(PermissionKeys.PLAN_ISSUE)
    @Transactional(readOnly = true)
    public PlanRevisionDiffView diff(UUID revisionId, UUID againstRevisionId) {
        PlanRevision current = authorizedQuery.getById(
                revisions, PlanRevision.class, PermissionKeys.PLAN_ISSUE, revisionId);
        PlanRevision previous = authorizedQuery.getById(
                revisions, PlanRevision.class, PermissionKeys.PLAN_ISSUE, againstRevisionId);
        if (!current.getCaseId().equals(previous.getCaseId())) {
            throw new NoSuchElementException(
                    "Revision " + againstRevisionId + " does not belong to the same case as " + revisionId);
        }

        List<PlanRevisionItem> currentItems = authorizedQuery.findAll(items, PlanRevisionItem.class,
                        PermissionKeys.PLAN_ISSUE,
                        (root, query, cb) -> cb.equal(root.get("planRevisionId"), revisionId),
                        Pageable.unpaged(Sort.by("sortOrder")))
                .getContent();
        List<PlanRevisionItem> previousItems = authorizedQuery.findAll(items, PlanRevisionItem.class,
                        PermissionKeys.PLAN_ISSUE,
                        (root, query, cb) -> cb.equal(root.get("planRevisionId"), againstRevisionId),
                        Pageable.unpaged(Sort.by("sortOrder")))
                .getContent();

        Map<UUID, PlanRevisionItem> currentByDefinition = currentItems.stream()
                .collect(toMap(PlanRevisionItem::getMilestoneDefinitionId, i -> i));
        Map<UUID, PlanRevisionItem> previousByDefinition = previousItems.stream()
                .collect(toMap(PlanRevisionItem::getMilestoneDefinitionId, i -> i));

        // Current's own order first (its sortOrder), then any previous-only
        // (REMOVED) milestones appended after -- deterministic, never recomputed
        // by the caller.
        Set<UUID> definitionIds = new LinkedHashSet<>();
        currentItems.forEach(i -> definitionIds.add(i.getMilestoneDefinitionId()));
        previousItems.forEach(i -> definitionIds.add(i.getMilestoneDefinitionId()));

        List<PlanRevisionDiffRowView> rows = definitionIds.stream()
                .map(id -> toDiffRow(id, currentByDefinition.get(id), previousByDefinition.get(id)))
                .toList();
        return new PlanRevisionDiffView(rows);
    }

    /**
     * Refuses ({@link PlanGateException}, 422) when {@code revisionId}'s current
     * status is not ISSUED -- a decision is one-shot, the same shape as {@link
     * PlanShapeService#decide}: issuing a second revision supersedes the first
     * (see {@link #issue}'s own comment on {@code plan_revision_one_outstanding_uq}),
     * so deciding an already-superseded (or already-decided) row is refused
     * rather than silently overwriting a prior decision.
     *
     * Records {@code plan.revision_decided} BEFORE calling {@link
     * CaseService#resume} -- {@code CauseBeforeEffectTest}'s whole reason for
     * existing: nine journey call sites once recorded their cause after the
     * reconcile it triggered, permanently misordering every audit row written
     * before 2026-08-29 (see CLAUDE.md). {@code resume} is called ONLY when
     * this decision is the case's FIRST-EVER APPROVED revision (QA Q22/Q23):
     * gate 2's approval is what lets a customer-template journey start actually
     * running, and a LATER revision being approved -- or rejected -- must never
     * disturb an already-ACTIVE case; re-holding on every revision would mean
     * an internal typo correction freezes a live project until the customer
     * replies again. {@link #hasAnyApprovedRevision} is checked BEFORE this
     * decision's own status is written, so it still reads {@code false} on the
     * very first approval and {@code true} on every one after -- and {@code
     * CaseService.resume} itself refuses ({@link CaseNotOnHoldException}) if
     * called on a case that is not ON_HOLD, so a second call here would be a
     * bug this guard exists to prevent, not merely a redundant one.
     */
    @RequirePermission(PermissionKeys.PLAN_APPROVE_SCHEDULE)
    @Transactional
    public PlanRevisionView decide(UUID revisionId, DecidePlanRequest request) {
        PlanRevision current = authorizedQuery.getById(
                revisions, PlanRevision.class, PermissionKeys.PLAN_APPROVE_SCHEDULE, revisionId);
        if (current.getStatus() != PlanRevisionStatus.ISSUED) {
            throw new PlanGateException(
                    "Revision " + revisionId + " has no outstanding schedule decision to decide");
        }

        boolean releasesTheHold = request.outcome() == PlanDecision.APPROVED
                && !hasAnyApprovedRevision(current.getCaseId());

        current.setStatus(request.outcome() == PlanDecision.APPROVED
                ? PlanRevisionStatus.APPROVED : PlanRevisionStatus.REJECTED);
        current.setDecidedAt(Instant.now(clock));
        current.setDecidedBy(contextProvider.principal().userId());
        current.setDecidedOnBehalfOf(request.decidedOnBehalfOfContactId());
        current.setDecisionNote(request.note());
        revisions.save(current);

        audit.record(AuditActions.PLAN_REVISION_DECIDED, "onboarding_case", current.getCaseId(),
                "Recorded a " + current.getStatus() + " schedule decision on revision "
                        + current.getRevisionNumber(),
                Map.of("planRevisionId", current.getId().toString(), "outcome", request.outcome().name()));

        // Cause-before-effect: the record above has already committed to this
        // transaction's session before this call, and CaseEngine.reconcile's own
        // events (triggered transitively through resume) are stamped no earlier
        // than resume's own case.resumed -- both AFTER plan.revision_decided.
        if (releasesTheHold) {
            caseService.resume(current.getCaseId());
        }

        List<PlanRevisionItem> itemRows = authorizedQuery.findAll(items, PlanRevisionItem.class,
                        PermissionKeys.PLAN_APPROVE_SCHEDULE,
                        (root, query, cb) -> cb.equal(root.get("planRevisionId"), revisionId),
                        Pageable.unpaged(Sort.by("sortOrder")))
                .getContent();
        return toView(current, itemRows);
    }

    /**
     * Whether {@code caseId} already has an earlier APPROVED revision -- checked
     * BEFORE the current one's own status is written above, so this reads {@code
     * false} on the case's first approval and {@code true} on every one after.
     * Resolved under the caller's own {@code plan.approve_schedule} gate, the
     * same "fetched with the caller's own permission" rule {@link
     * PlanShapeService} follows throughout its own reads.
     */
    private boolean hasAnyApprovedRevision(UUID caseId) {
        return !authorizedQuery.findAll(revisions, PlanRevision.class, PermissionKeys.PLAN_APPROVE_SCHEDULE,
                        (root, query, cb) -> cb.and(
                                cb.equal(root.get("caseId"), caseId),
                                cb.equal(root.get("status"), PlanRevisionStatus.APPROVED)),
                        Pageable.unpaged())
                .getContent().isEmpty();
    }

    /**
     * Snapshots every milestone whose OWN {@code portalVisible} flag is true AND
     * whose stage's {@code portalVisible} flag is true, as both stand RIGHT NOW
     * -- mirroring {@code workflow.PlanShapeService.render}'s top-down filter (a
     * hidden stage drops its whole subtree, milestones included, even one that
     * is itself portal-visible). Captured values (due date, owner) are read from
     * the live {@link Milestone} row at the moment this method runs and copied
     * into a new, immutable {@link PlanRevisionItem} -- a later change to that
     * Milestone row must not alter an already-issued revision, which is the
     * whole reason the item table exists rather than a view over live data.
     */
    private List<PlanRevisionItem> snapshotPortalVisibleMilestones(Case c, PlanRevision revision, Instant now) {
        StagesAndDefinitions graph = stagesAndDefinitionsOf(c.getVersionId());
        List<Stage> stageRows = graph.stages();
        Map<UUID, List<MilestoneDefinition>> definitionsByStage = graph.definitionsByStage();

        Map<UUID, Milestone> milestoneByDefinitionId = authorizedQuery.findAll(milestones, Milestone.class,
                        PermissionKeys.PLAN_ISSUE,
                        (root, query, cb) -> cb.equal(root.get("caseId"), c.getId()),
                        Pageable.unpaged())
                .getContent().stream()
                .collect(toMap(Milestone::getMilestoneDefinitionId, m -> m));

        List<PlanRevisionItem> itemRows = new ArrayList<>();
        int sortOrder = 0;
        for (Stage stage : stageRows) {
            if (!stage.isPortalVisible()) continue;     // a hidden stage drops its whole subtree
            for (MilestoneDefinition def : definitionsByStage.getOrDefault(stage.getId(), List.of())) {
                if (!def.isPortalVisible()) continue;
                Milestone m = milestoneByDefinitionId.get(def.getId());
                if (m == null) continue;                // not yet instantiated (a later/skipped stage)
                sortOrder++;
                itemRows.add(new PlanRevisionItem(Uuid7.generate(), revision.getTenantId(), revision.getId(),
                        c.getId(), m.getId(), def.getId(), stage.getName(), def.getName(), m.getDueDate(),
                        m.getOwnerUserId(), def.getEstimatedDurationDays(), true, sortOrder, now));
            }
        }
        return itemRows;
    }

    /**
     * Reads the pinned version's Stage/MilestoneDefinition rows under {@code
     * workflow.view} -- mirroring {@code journey.CaseService.readDefinition}'s
     * own hardcoded choice of that key, the same already-acknowledged gap
     * CLAUDE.md documents for {@code CaseService.roadmap}/{@code toView}
     * ("viewing a case's full representation is gated by more than
     * case.view"). Not a free design choice: neither {@code Stage} nor {@code
     * MilestoneDefinition} has a {@code ResourceAuthorizationDescriptor} --
     * {@code workflow.view}/{@code workflow.manage} are ALL-only in the
     * catalog, so none was ever needed -- and {@code
     * DescriptorRegistry.forEntity} throws {@code IllegalStateException} for
     * any OTHER permission key that does not resolve to ALL for the caller,
     * rather than merely denying. {@code plan.issue} (RECORD-scoped) cannot
     * substitute here for that reason: a TEAM-scoped plan.issue holder would
     * crash this read, not merely fail it closed.
     */
    private StagesAndDefinitions stagesAndDefinitionsOf(UUID versionId) {
        List<Stage> stageRows = authorizedQuery.findAll(stages, Stage.class, PermissionKeys.WORKFLOW_VIEW,
                        (root, query, cb) -> cb.equal(root.get("versionId"), versionId),
                        Pageable.unpaged(Sort.by("ordinal")))
                .getContent();
        List<MilestoneDefinition> definitionRows = authorizedQuery.findAll(milestoneDefinitions,
                        MilestoneDefinition.class, PermissionKeys.WORKFLOW_VIEW,
                        (root, query, cb) -> cb.equal(root.get("versionId"), versionId),
                        Pageable.unpaged(Sort.by("ordinal")))
                .getContent();
        return new StagesAndDefinitions(stageRows,
                definitionRows.stream().collect(groupingBy(MilestoneDefinition::getStageId)));
    }

    private record StagesAndDefinitions(List<Stage> stages, Map<UUID, List<MilestoneDefinition>> definitionsByStage) {}

    private PlanRevisionView toView(PlanRevision r, List<PlanRevisionItem> itemRows) {
        List<PlanRevisionItemView> itemViews = itemRows.stream().map(this::toItemView).toList();
        return new PlanRevisionView(r.getId(), r.getCaseId(), r.getRevisionNumber(), r.getStatus(),
                r.getIssuedAt(), r.getIssuedBy(), r.getIssueNote(), r.getDecidedAt(), r.getDecidedBy(),
                r.getDecidedOnBehalfOf(), r.getDecisionNote(), itemViews);
    }

    /**
     * {@code current}/{@code previous} are null exactly when the milestone is
     * absent from that side -- {@link ChangeKind#ADDED} has no previous row,
     * {@link ChangeKind#REMOVED} has no current row. When both sides carry the
     * milestone, a due-date difference is reported before an owner difference:
     * gate 2 is fundamentally about the SCHEDULE (this class's own javadoc), and
     * nothing in this task's brief exercises both changing at once.
     */
    private PlanRevisionDiffRowView toDiffRow(UUID definitionId, PlanRevisionItem current, PlanRevisionItem previous) {
        String name = current != null ? current.getMilestoneName() : previous.getMilestoneName();
        LocalDate previousDueDate = previous != null ? previous.getDueDate() : null;
        LocalDate currentDueDate = current != null ? current.getDueDate() : null;
        UUID previousOwnerUserId = previous != null ? previous.getOwnerUserId() : null;
        UUID currentOwnerUserId = current != null ? current.getOwnerUserId() : null;

        ChangeKind changeKind;
        if (current == null) {
            changeKind = ChangeKind.REMOVED;
        } else if (previous == null) {
            changeKind = ChangeKind.ADDED;
        } else if (!Objects.equals(previousDueDate, currentDueDate)) {
            changeKind = ChangeKind.DATE_CHANGED;
        } else if (!Objects.equals(previousOwnerUserId, currentOwnerUserId)) {
            changeKind = ChangeKind.OWNER_CHANGED;
        } else {
            changeKind = ChangeKind.UNCHANGED;
        }
        return new PlanRevisionDiffRowView(definitionId, name, previousDueDate, currentDueDate,
                previousOwnerUserId, currentOwnerUserId, changeKind);
    }

    private PlanRevisionItemView toItemView(PlanRevisionItem i) {
        return new PlanRevisionItemView(i.getId(), i.getMilestoneId(), i.getMilestoneDefinitionId(),
                i.getStageName(), i.getMilestoneName(), i.getDueDate(), i.getOwnerUserId(),
                i.getEstimatedDurationDays(), i.isPortalVisible(), i.getSortOrder());
    }
}
