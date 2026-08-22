package co.ara.onboarding.workflow;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.AttributeRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.BranchRuleRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.ConditionRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.MilestoneRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.RequirementRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.StageRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionView.AttributeView;
import co.ara.onboarding.workflow.WorkflowDefinitionView.BranchRuleView;
import co.ara.onboarding.workflow.WorkflowDefinitionView.ConditionView;
import co.ara.onboarding.workflow.WorkflowDefinitionView.MilestoneView;
import co.ara.onboarding.workflow.WorkflowDefinitionView.RequirementView;
import co.ara.onboarding.workflow.WorkflowDefinitionView.StageView;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Template and draft authoring (spec 6). A draft is edited as one whole document --
 * see {@link WorkflowDefinitionRequest} -- rather than per-element, so reordering
 * stages or rewiring a branch rule is one atomic write instead of a sequence that can
 * leave dangling references between calls.
 *
 * Every read reaches its entities through {@link AuthorizedQuery}, even though
 * workflow.view and workflow.manage are both ALL-only and resolve to an unconditional
 * match: AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly binds to
 * every *Service in this package regardless of whether the permission it authorizes
 * happens to need per-record scoping, and a repository finder called directly is
 * exactly the silent bypass shape that guard exists to catch.
 */
@Service
public class WorkflowService {

    private final WorkflowTemplateRepository templates;
    private final WorkflowVersionRepository versions;
    private final StageRepository stages;
    private final MilestoneDefinitionRepository milestoneDefinitions;
    private final MilestoneDependencyRepository dependencies;
    private final RequirementDefinitionRepository requirementDefinitions;
    private final AttributeDefinitionRepository attributeDefinitions;
    private final BranchRuleRepository branchRules;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final AuditRecorder audit;

    public WorkflowService(WorkflowTemplateRepository templates,
                           WorkflowVersionRepository versions,
                           StageRepository stages,
                           MilestoneDefinitionRepository milestoneDefinitions,
                           MilestoneDependencyRepository dependencies,
                           RequirementDefinitionRepository requirementDefinitions,
                           AttributeDefinitionRepository attributeDefinitions,
                           BranchRuleRepository branchRules,
                           AuthorizedQuery authorizedQuery,
                           AuthContextProvider contextProvider,
                           AuditRecorder audit) {
        this.templates = templates;
        this.versions = versions;
        this.stages = stages;
        this.milestoneDefinitions = milestoneDefinitions;
        this.dependencies = dependencies;
        this.requirementDefinitions = requirementDefinitions;
        this.attributeDefinitions = attributeDefinitions;
        this.branchRules = branchRules;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.audit = audit;
    }

    @RequirePermission(PermissionKeys.WORKFLOW_MANAGE)
    @Transactional
    public WorkflowTemplateView createTemplate(String name, String description) {
        UUID actor = contextProvider.principal().userId();

        WorkflowTemplate t = new WorkflowTemplate();
        t.setId(Uuid7.generate());
        t.setTenantId(TenantContext.getRequired());
        t.setName(name);
        t.setDescription(description);
        t.setStatus(TemplateStatus.ACTIVE);
        t.setCreatedBy(actor);
        templates.save(t);

        audit.record(AuditActions.WORKFLOW_TEMPLATE_CREATED, "workflow_template", t.getId(),
                "Created workflow template " + t.getName(), Map.of());
        // Built from the entity this method's own WORKFLOW_MANAGE gate already
        // authorized, not re-read under WORKFLOW_VIEW -- see replaceDraft below for
        // why that distinction matters.
        return toTemplateView(t, PermissionKeys.WORKFLOW_MANAGE);
    }

    @RequirePermission(PermissionKeys.WORKFLOW_VIEW)
    @Transactional(readOnly = true)
    public List<WorkflowTemplateView> listTemplates() {
        return authorizedQuery.findAll(templates, WorkflowTemplate.class, PermissionKeys.WORKFLOW_VIEW,
                        null, Pageable.unpaged(Sort.by("name")))
                .map(t -> toTemplateView(t, PermissionKeys.WORKFLOW_VIEW))
                .getContent();
    }

    @RequirePermission(PermissionKeys.WORKFLOW_VIEW)
    @Transactional(readOnly = true)
    public WorkflowTemplateView getTemplate(UUID templateId) {
        WorkflowTemplate t = authorizedQuery.getById(templates, WorkflowTemplate.class,
                PermissionKeys.WORKFLOW_VIEW, templateId);
        return toTemplateView(t, PermissionKeys.WORKFLOW_VIEW);
    }

    @RequirePermission(PermissionKeys.WORKFLOW_MANAGE)
    @Transactional
    public void deactivateTemplate(UUID templateId) {
        WorkflowTemplate t = authorizedQuery.getById(templates, WorkflowTemplate.class,
                PermissionKeys.WORKFLOW_MANAGE, templateId);
        t.setStatus(TemplateStatus.INACTIVE);
        templates.save(t);

        audit.record(AuditActions.WORKFLOW_TEMPLATE_DEACTIVATED, "workflow_template", t.getId(),
                "Deactivated workflow template " + t.getName(), Map.of());
    }

    /**
     * Deep-copies currentVersionId's graph by reading it into a
     * WorkflowDefinitionRequest and calling replaceDraft on a new empty draft -- one
     * code path for copying and editing, so a field added to the graph cannot be
     * forgotten in the copy. If nothing has ever been published, the new draft starts
     * empty.
     *
     * The existence check runs before the insert: the only guard V12 gives is a
     * partial unique index (workflow_version_one_draft_per_template), which would
     * otherwise surface as a generic DataIntegrityViolationException rather than the
     * named exception a caller can act on.
     */
    @RequirePermission(PermissionKeys.WORKFLOW_MANAGE)
    @Transactional
    public UUID createDraft(UUID templateId) {
        WorkflowTemplate template = authorizedQuery.getById(templates, WorkflowTemplate.class,
                PermissionKeys.WORKFLOW_MANAGE, templateId);

        boolean draftExists = authorizedQuery.findAll(versions, WorkflowVersion.class,
                        PermissionKeys.WORKFLOW_MANAGE,
                        (root, query, cb) -> cb.and(
                                cb.equal(root.get("templateId"), templateId),
                                cb.equal(root.get("status"), VersionStatus.DRAFT)),
                        Pageable.ofSize(1))
                .hasContent();
        if (draftExists) {
            throw new DraftAlreadyExistsException(templateId);
        }

        int nextVersionNo = authorizedQuery.findAll(versions, WorkflowVersion.class,
                        PermissionKeys.WORKFLOW_MANAGE,
                        (root, query, cb) -> cb.equal(root.get("templateId"), templateId),
                        PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "versionNo")))
                .stream().findFirst().map(v -> v.getVersionNo() + 1).orElse(1);

        WorkflowVersion draft = new WorkflowVersion();
        draft.setId(Uuid7.generate());
        draft.setTenantId(TenantContext.getRequired());
        draft.setTemplateId(templateId);
        draft.setVersionNo(nextVersionNo);
        draft.setStatus(VersionStatus.DRAFT);
        versions.saveAndFlush(draft);

        // Only the deep-copy path calls replaceDraft: an empty draft has nothing to
        // write, and going through replaceDraft anyway would bump lockVersion from 0
        // to 1 before the caller ever sees it. Each branch records its own
        // creation event -- replaceDraft's own workflow.draft_saved event (fired
        // below, inside it, for the copy branch) is a true record that the graph
        // was written, but says nothing about the draft having been *created by
        // copying* a specific version, and the empty branch never calls
        // replaceDraft at all, so without a call here that path is silently
        // unaudited.
        if (template.getCurrentVersionId() != null) {
            WorkflowVersion published = authorizedQuery.getById(versions, WorkflowVersion.class,
                    PermissionKeys.WORKFLOW_MANAGE, template.getCurrentVersionId());
            WorkflowDefinitionRequest source = toRequest(published, PermissionKeys.WORKFLOW_MANAGE);
            // The copy targets the brand-new draft, whose lockVersion is 0 regardless
            // of what the published version's own lock version happened to be.
            WorkflowDefinitionRequest copyRequest =
                    new WorkflowDefinitionRequest(source.stages(), source.attributes(), 0L);

            audit.record(AuditActions.WORKFLOW_DRAFT_SAVED, "workflow_version", draft.getId(),
                    "Created draft v" + draft.getVersionNo() + " of " + template.getName()
                            + " by copying v" + published.getVersionNo(),
                    Map.of("copiedFromVersionNo", published.getVersionNo()));
            replaceDraft(draft.getId(), copyRequest);
        } else {
            audit.record(AuditActions.WORKFLOW_DRAFT_SAVED, "workflow_version", draft.getId(),
                    "Created empty draft v" + draft.getVersionNo() + " of " + template.getName(),
                    Map.of());
        }
        return draft.getId();
    }

    @RequirePermission(PermissionKeys.WORKFLOW_VIEW)
    @Transactional(readOnly = true)
    public WorkflowDefinitionView getDefinition(UUID versionId) {
        WorkflowVersion version = authorizedQuery.getById(versions, WorkflowVersion.class,
                PermissionKeys.WORKFLOW_VIEW, versionId);
        return toView(version, PermissionKeys.WORKFLOW_VIEW);
    }

    /**
     * Package-private: lets {@link PublishService} and {@link WorkflowController}
     * build a definition view under WORKFLOW_MANAGE -- the permission the caller's
     * own gate already checked -- rather than re-authorizing under WORKFLOW_VIEW,
     * which is exactly the "manage without view" gap {@code replaceDraft}'s own
     * return statement avoids above.
     *
     * @Transactional in its own right, not merely relying on an already-open one:
     * PublishService.publish calls this while its own transaction is still open, so
     * it would work there either way, but WorkflowController.newDraft calls it
     * AFTER createDraft's transaction has already committed and closed. Without its
     * own boundary here, TenantTransactionBinder's pointcut (which matches
     * @Transactional, not "some transaction happens to be open") never fires, the
     * tenant is never bound on this call, and the read finds nothing under RLS --
     * confirmed empirically: the generated SQL was missing both the Hibernate
     * tenantFilter predicate and the app.tenant_id GUC that RLS reads, and the
     * call that should have found the row it had just inserted a moment earlier
     * instead threw NoSuchElementException.
     */
    @Transactional(readOnly = true)
    WorkflowDefinitionView getDefinitionAs(UUID versionId, String permissionKey) {
        WorkflowVersion version = authorizedQuery.getById(versions, WorkflowVersion.class,
                permissionKey, versionId);
        return toView(version, permissionKey);
    }

    /**
     * The one method worth writing out, because its ordering is what makes the graph
     * consistent. Resolved through AuthorizedQuery, never by raw id: this is a write
     * path taking an id from a URL, which is the shape that produced three
     * escalations in sub-project 1.
     */
    @RequirePermission(PermissionKeys.WORKFLOW_MANAGE)
    @Transactional
    public WorkflowDefinitionView replaceDraft(UUID versionId, WorkflowDefinitionRequest request) {
        WorkflowVersion version = authorizedQuery.getById(
                versions, WorkflowVersion.class, PermissionKeys.WORKFLOW_MANAGE, versionId);

        if (version.getStatus() != VersionStatus.DRAFT) {
            throw new VersionNotEditableException(versionId);
        }
        if (request.lockVersion() != version.getLockVersion()) {
            throw new OptimisticLockingFailureException(
                    "Draft " + versionId + " was modified by someone else");
        }

        validateRequestShape(request);   // keys unique, references resolvable, ordinals dense

        // Children first, parents second: branch_rule and milestone_dependency
        // reference stage and milestone_definition, so deleting in the other order
        // trips the FKs. The trigger permits these deletes only because the version
        // is DRAFT.
        branchRules.deleteByVersionId(versionId);
        dependencies.deleteByVersionId(versionId);
        requirementDefinitions.deleteByVersionId(versionId);
        milestoneDefinitions.deleteByVersionId(versionId);
        stages.deleteByVersionId(versionId);
        attributeDefinitions.deleteByVersionId(versionId);

        // Pass 1: stages and milestones, recording key -> assigned id. The entity
        // itself is kept alongside the id so pass 2 can mutate a stage's own
        // fallbackNextStageId in place rather than re-fetching it through a
        // repository finder, which AuthorizationCoverageTest forbids outside
        // AuthorizedQuery.
        Map<String, UUID> stageIds = new LinkedHashMap<>();
        Map<String, Stage> stageEntities = new LinkedHashMap<>();
        Map<String, UUID> milestoneIds = new LinkedHashMap<>();
        int stageOrdinal = 1;
        for (var s : request.stages()) {
            Stage stage = newStage(version, s, stageOrdinal++);
            // Reassigned, not discarded: Stage's id is a pre-assigned Uuid7, not
            // @GeneratedValue, and BaseEntity has no @Version/Persistable, so
            // Spring Data's isNew() sees a non-null id on this brand-new entity and
            // calls entityManager.merge() rather than persist(). merge() returns a
            // DIFFERENT managed instance and leaves the original `stage` detached --
            // pass 2's setFallbackNextStageId() below was mutating that detached
            // copy, so no stage's fallback ever actually reached the database. Only
            // surfaced once CaseEngine (Task 15) read fallbackNextStageId at
            // runtime; every WorkflowAuthoringTest assertion happened to read the
            // in-memory WorkflowDefinitionView this same method returns, which is
            // built from these (detached but correctly mutated) Java objects.
            stage = stages.save(stage);
            stageIds.put(s.key(), stage.getId());
            stageEntities.put(s.key(), stage);

            int milestoneOrdinal = 1;
            for (var m : s.milestones()) {
                MilestoneDefinition definition = newMilestone(version, stage, m, milestoneOrdinal++);
                milestoneDefinitions.save(definition);
                milestoneIds.put(m.key(), definition.getId());

                int requirementOrdinal = 1;
                for (var r : m.requirements()) {
                    requirementDefinitions.save(
                            newRequirement(version, definition, r, requirementOrdinal++));
                }
            }
        }

        // Pass 2: everything that references a key assigned in pass 1. Two passes
        // rather than one is what allows a branch rule to target a stage declared
        // after it, and a dependency to name a milestone in the same request.
        for (var s : request.stages()) {
            UUID stageId = stageIds.get(s.key());
            if (s.fallbackNextStageKey() != null) {
                stageEntities.get(s.key())
                        .setFallbackNextStageId(resolve(stageIds, s.fallbackNextStageKey()));
            }
            int ruleOrdinal = 1;
            for (var rule : s.branchRules()) {
                branchRules.save(newBranchRule(version, stageId, rule, ruleOrdinal++,
                        resolve(stageIds, rule.targetStageKey())));
            }
            for (var m : s.milestones()) {
                for (String dependsOn : orEmpty(m.dependsOnMilestoneKeys())) {
                    dependencies.save(newDependency(version,
                            milestoneIds.get(m.key()), resolve(milestoneIds, dependsOn)));
                }
            }
        }

        int attributeOrdinal = 1;
        for (var a : request.attributes()) {
            attributeDefinitions.save(newAttribute(version, a, attributeOrdinal++));
        }

        version.setLockVersion(version.getLockVersion() + 1);
        audit.record(AuditActions.WORKFLOW_DRAFT_SAVED, "workflow_version", versionId,
                "Saved draft v" + version.getVersionNo() + " of "
                        + templateName(version, PermissionKeys.WORKFLOW_MANAGE),
                Map.of("stages", request.stages().size()));
        // Built from the version this method's own WORKFLOW_MANAGE gate already
        // fetched and authorized, not re-read via getDefinition's WORKFLOW_VIEW --
        // the same "fetched with the write permission, not the read one" rule
        // CustomerService.update follows. RoleService has no "manage implies view"
        // constraint, so a role holding workflow.manage without workflow.view would
        // otherwise pass this method's gate, write the whole graph, and then hit a
        // 404-shaped NoSuchElementException on its own return value -- rolling back
        // a write it was correctly authorized to make.
        return toView(version, PermissionKeys.WORKFLOW_MANAGE);
    }

    @RequirePermission(PermissionKeys.WORKFLOW_MANAGE)
    @Transactional
    public void discardDraft(UUID versionId) {
        WorkflowVersion version = authorizedQuery.getById(versions, WorkflowVersion.class,
                PermissionKeys.WORKFLOW_MANAGE, versionId);
        if (version.getStatus() != VersionStatus.DRAFT) {
            throw new VersionNotEditableException(versionId);
        }

        branchRules.deleteByVersionId(versionId);
        dependencies.deleteByVersionId(versionId);
        requirementDefinitions.deleteByVersionId(versionId);
        milestoneDefinitions.deleteByVersionId(versionId);
        stages.deleteByVersionId(versionId);
        attributeDefinitions.deleteByVersionId(versionId);
        versions.delete(version);

        audit.record(AuditActions.WORKFLOW_DRAFT_DISCARDED, "workflow_version", versionId,
                "Discarded draft v" + version.getVersionNo() + " of template " + version.getTemplateId(),
                Map.of());
    }

    // ---- structural validation -------------------------------------------------

    /**
     * Checks only what is structurally impossible: duplicate keys, and a
     * branch/fallback/dependency target that names no declared element. The five
     * semantic publish-time validations (spec) belong to Task 7, not here -- a draft
     * is allowed to be temporarily incoherent while an admin edits it.
     */
    private void validateRequestShape(WorkflowDefinitionRequest request) {
        Set<String> stageKeys = new HashSet<>();
        Set<String> milestoneKeys = new HashSet<>();
        Set<String> attributeKeys = new HashSet<>();

        for (var s : request.stages()) {
            if (!stageKeys.add(s.key())) {
                throw new IllegalArgumentException("Duplicate stage key: " + s.key());
            }
            for (var m : s.milestones()) {
                if (!milestoneKeys.add(m.key())) {
                    throw new IllegalArgumentException("Duplicate milestone key: " + m.key());
                }
            }
        }
        for (var a : request.attributes()) {
            if (!attributeKeys.add(a.key())) {
                throw new IllegalArgumentException("Duplicate attribute key: " + a.key());
            }
        }

        for (var s : request.stages()) {
            if (s.fallbackNextStageKey() != null && !stageKeys.contains(s.fallbackNextStageKey())) {
                throw new UnknownReferenceException(s.fallbackNextStageKey());
            }
            for (var rule : s.branchRules()) {
                if (!stageKeys.contains(rule.targetStageKey())) {
                    throw new UnknownReferenceException(rule.targetStageKey());
                }
            }
            for (var m : s.milestones()) {
                for (String dependsOn : orEmpty(m.dependsOnMilestoneKeys())) {
                    if (!milestoneKeys.contains(dependsOn)) {
                        throw new UnknownReferenceException(dependsOn);
                    }
                }
            }
        }
    }

    private UUID resolve(Map<String, UUID> ids, String key) {
        UUID id = ids.get(key);
        if (id == null) {
            throw new UnknownReferenceException(key);
        }
        return id;
    }

    // ---- entity construction ----------------------------------------------------

    private Stage newStage(WorkflowVersion version, StageRequest s, int ordinal) {
        Stage stage = new Stage();
        stage.setId(Uuid7.generate());
        stage.setTenantId(version.getTenantId());
        stage.setVersionId(version.getId());
        stage.setOrdinal(ordinal);
        stage.setName(s.name());
        stage.setResponsibleDepartmentId(s.responsibleDepartmentId());
        stage.setRequiresApproval(s.requiresApproval());
        stage.setAutoAdvance(s.autoAdvance());
        stage.setPortalVisible(s.portalVisible());
        stage.setSlaDays(s.slaDays());
        stage.setWriteScope(s.writeScope() == null ? WriteScope.ANY : s.writeScope());
        stage.setNotificationTemplateKey(s.notificationTemplateKey());
        stage.setEntryCondition(toCondition(s.entryCondition()));
        return stage;
    }

    private MilestoneDefinition newMilestone(WorkflowVersion version, Stage stage,
                                             MilestoneRequest m, int ordinal) {
        MilestoneDefinition definition = new MilestoneDefinition();
        definition.setId(Uuid7.generate());
        definition.setTenantId(version.getTenantId());
        definition.setVersionId(version.getId());
        definition.setStageId(stage.getId());
        definition.setOrdinal(ordinal);
        definition.setName(m.name());
        definition.setDescription(m.description());
        definition.setEstimatedDurationDays(m.estimatedDurationDays());
        return definition;
    }

    private RequirementDefinition newRequirement(WorkflowVersion version, MilestoneDefinition definition,
                                                 RequirementRequest r, int ordinal) {
        RequirementDefinition requirement = new RequirementDefinition();
        requirement.setId(Uuid7.generate());
        requirement.setTenantId(version.getTenantId());
        requirement.setVersionId(version.getId());
        requirement.setMilestoneDefinitionId(definition.getId());
        requirement.setOrdinal(ordinal);
        requirement.setKind(r.kind());
        requirement.setLabel(r.label());
        requirement.setWeight(r.weight());
        requirement.setMandatory(r.mandatory());
        requirement.setDocumentCategory(r.documentCategory());
        requirement.setApproverRelationship(
                r.approverRelationship() == null ? null : r.approverRelationship().name());
        return requirement;
    }

    private BranchRule newBranchRule(WorkflowVersion version, UUID stageId, BranchRuleRequest rule,
                                     int ordinal, UUID targetStageId) {
        BranchRule branchRule = new BranchRule();
        branchRule.setId(Uuid7.generate());
        branchRule.setTenantId(version.getTenantId());
        branchRule.setVersionId(version.getId());
        branchRule.setStageId(stageId);
        branchRule.setOrdinal(ordinal);
        branchRule.setCondition(toCondition(rule.condition()));
        branchRule.setTargetStageId(targetStageId);
        return branchRule;
    }

    /**
     * dependsOnMilestoneKeys is an optional field: a milestone with no
     * dependencies is the common case, and a client is entitled to omit the
     * key entirely rather than send an empty array. Both call sites iterate it
     * directly, so null must be normalised at the boundary, not assumed away.
     */
    private static List<String> orEmpty(List<String> keys) {
        return keys == null ? List.of() : keys;
    }

    private MilestoneDependency newDependency(WorkflowVersion version, UUID milestoneId, UUID dependsOnId) {
        MilestoneDependency dependency = new MilestoneDependency();
        dependency.setId(Uuid7.generate());
        dependency.setTenantId(version.getTenantId());
        dependency.setVersionId(version.getId());
        dependency.setMilestoneDefinitionId(milestoneId);
        dependency.setDependsOnMilestoneDefinitionId(dependsOnId);
        return dependency;
    }

    private AttributeDefinition newAttribute(WorkflowVersion version, AttributeRequest a, int ordinal) {
        AttributeDefinition attribute = new AttributeDefinition();
        attribute.setId(Uuid7.generate());
        attribute.setTenantId(version.getTenantId());
        attribute.setVersionId(version.getId());
        attribute.setOrdinal(ordinal);
        attribute.setKey(a.key());
        attribute.setLabel(a.label());
        attribute.setDataType(a.dataType());
        attribute.setRequired(a.required());
        attribute.setAllowedValues(a.allowedValues() == null ? null : a.allowedValues().toArray(new String[0]));
        return attribute;
    }

    private Condition toCondition(ConditionRequest c) {
        Condition condition = new Condition();
        if (c != null) {
            condition.setSource(c.source());
            condition.setKey(c.key());
            condition.setOperator(c.operator());
            condition.setValue(c.value());
            condition.setValues(c.values() == null ? null : c.values().toArray(new String[0]));
        }
        return condition;
    }

    // ---- reading ------------------------------------------------------------

    /**
     * permissionKey is the caller's own gate, not always WORKFLOW_VIEW: a method
     * gated on WORKFLOW_MANAGE (replaceDraft, createDraft's copy path) must resolve
     * its own supporting reads under WORKFLOW_MANAGE too, or a role holding manage
     * without view would pass the method's gate and then fail on its own return
     * value or audit description.
     */
    private String templateName(WorkflowVersion version, String permissionKey) {
        return authorizedQuery.getById(templates, WorkflowTemplate.class,
                permissionKey, version.getTemplateId()).getName();
    }

    private WorkflowTemplateView toTemplateView(WorkflowTemplate t, String permissionKey) {
        Integer versionNo = null;
        if (t.getCurrentVersionId() != null) {
            versionNo = authorizedQuery.getById(versions, WorkflowVersion.class,
                    permissionKey, t.getCurrentVersionId()).getVersionNo();
        }
        return new WorkflowTemplateView(t.getId(), t.getName(), t.getDescription(), t.getStatus(),
                t.getCurrentVersionId(), versionNo);
    }

    private <T> List<T> readChildren(Class<T> type, JpaSpecificationExecutor<T> repo,
                                     UUID versionId, String sortField, String permissionKey) {
        Specification<T> byVersion = (root, query, cb) -> cb.equal(root.get("versionId"), versionId);
        return authorizedQuery.findAll(repo, type, permissionKey, byVersion,
                        Pageable.unpaged(Sort.by(sortField)))
                .getContent();
    }

    private WorkflowDefinitionView toView(WorkflowVersion version, String permissionKey) {
        UUID versionId = version.getId();
        List<Stage> stageEntities = readChildren(Stage.class, stages, versionId, "ordinal", permissionKey);
        List<MilestoneDefinition> milestoneEntities =
                readChildren(MilestoneDefinition.class, milestoneDefinitions, versionId, "ordinal", permissionKey);
        List<RequirementDefinition> requirementEntities =
                readChildren(RequirementDefinition.class, requirementDefinitions, versionId, "ordinal", permissionKey);
        List<BranchRule> branchRuleEntities =
                readChildren(BranchRule.class, branchRules, versionId, "ordinal", permissionKey);
        List<MilestoneDependency> dependencyEntities =
                readChildren(MilestoneDependency.class, dependencies, versionId, "id", permissionKey);
        List<AttributeDefinition> attributeEntities =
                readChildren(AttributeDefinition.class, attributeDefinitions, versionId, "ordinal", permissionKey);

        Map<UUID, List<RequirementDefinition>> requirementsByMilestone = new LinkedHashMap<>();
        for (var r : requirementEntities) {
            requirementsByMilestone.computeIfAbsent(r.getMilestoneDefinitionId(), k -> new ArrayList<>()).add(r);
        }
        Map<UUID, List<UUID>> dependsOnByMilestone = new LinkedHashMap<>();
        for (var d : dependencyEntities) {
            dependsOnByMilestone.computeIfAbsent(d.getMilestoneDefinitionId(), k -> new ArrayList<>())
                    .add(d.getDependsOnMilestoneDefinitionId());
        }
        Map<UUID, List<MilestoneDefinition>> milestonesByStage = new LinkedHashMap<>();
        for (var m : milestoneEntities) {
            milestonesByStage.computeIfAbsent(m.getStageId(), k -> new ArrayList<>()).add(m);
        }
        Map<UUID, List<BranchRule>> branchRulesByStage = new LinkedHashMap<>();
        for (var b : branchRuleEntities) {
            branchRulesByStage.computeIfAbsent(b.getStageId(), k -> new ArrayList<>()).add(b);
        }

        List<StageView> stageViews = stageEntities.stream()
                .map(s -> toStageView(s,
                        milestonesByStage.getOrDefault(s.getId(), List.of()),
                        branchRulesByStage.getOrDefault(s.getId(), List.of()),
                        requirementsByMilestone, dependsOnByMilestone))
                .toList();
        List<AttributeView> attributeViews = attributeEntities.stream().map(this::toAttributeView).toList();

        return new WorkflowDefinitionView(version.getId(), version.getTemplateId(), version.getVersionNo(),
                version.getStatus(), version.getLockVersion(), version.getPublishedAt(),
                stageViews, attributeViews);
    }

    private StageView toStageView(Stage s, List<MilestoneDefinition> milestones, List<BranchRule> rules,
                                  Map<UUID, List<RequirementDefinition>> requirementsByMilestone,
                                  Map<UUID, List<UUID>> dependsOnByMilestone) {
        List<MilestoneView> milestoneViews = milestones.stream()
                .map(m -> toMilestoneView(m,
                        requirementsByMilestone.getOrDefault(m.getId(), List.of()),
                        dependsOnByMilestone.getOrDefault(m.getId(), List.of())))
                .toList();
        List<BranchRuleView> ruleViews = rules.stream().map(this::toBranchRuleView).toList();

        return new StageView(s.getId(), s.getId().toString(), s.getName(), s.getResponsibleDepartmentId(),
                s.isRequiresApproval(), s.isAutoAdvance(), s.isPortalVisible(), s.getSlaDays(),
                s.getWriteScope(), s.getNotificationTemplateKey(), toConditionView(s.getEntryCondition()),
                s.getFallbackNextStageId() == null ? null : s.getFallbackNextStageId().toString(),
                s.getFallbackNextStageId(), milestoneViews, ruleViews);
    }

    private MilestoneView toMilestoneView(MilestoneDefinition m, List<RequirementDefinition> requirements,
                                          List<UUID> dependsOnIds) {
        List<RequirementView> requirementViews = requirements.stream().map(this::toRequirementView).toList();
        List<String> dependsOnKeys = dependsOnIds.stream().map(UUID::toString).toList();
        return new MilestoneView(m.getId(), m.getId().toString(), m.getName(), m.getDescription(),
                m.getEstimatedDurationDays(), dependsOnKeys, dependsOnIds, requirementViews);
    }

    private RequirementView toRequirementView(RequirementDefinition r) {
        RelationshipType relationship = r.getApproverRelationship() == null
                ? null : RelationshipType.valueOf(r.getApproverRelationship());
        return new RequirementView(r.getId(), r.getKind(), r.getLabel(), r.getWeight(), r.isMandatory(),
                r.getDocumentCategory(), relationship);
    }

    private BranchRuleView toBranchRuleView(BranchRule b) {
        return new BranchRuleView(b.getId(), toConditionView(b.getCondition()),
                b.getTargetStageId().toString(), b.getTargetStageId());
    }

    private AttributeView toAttributeView(AttributeDefinition a) {
        return new AttributeView(a.getId(), a.getKey(), a.getLabel(), a.getDataType(), a.isRequired(),
                a.getAllowedValues() == null ? null : List.of(a.getAllowedValues()));
    }

    private ConditionView toConditionView(Condition c) {
        if (c == null || c.getSource() == null) return null;
        return new ConditionView(c.getSource(), c.getKey(), c.getOperator(), c.getValue(),
                c.getValues() == null ? null : List.of(c.getValues()));
    }

    // ---- copying (createDraft) -----------------------------------------------

    /**
     * Reads a version's whole graph back into the request shape used to write it,
     * via the view: the view already echoes every cross-reference as the referenced
     * row's own id in string form, which is exactly what a request's client-local
     * keys need to be for replaceDraft to resolve them correctly.
     */
    private WorkflowDefinitionRequest toRequest(WorkflowVersion version, String permissionKey) {
        WorkflowDefinitionView view = toView(version, permissionKey);
        return new WorkflowDefinitionRequest(
                view.stages().stream().map(this::toStageRequest).toList(),
                view.attributes().stream().map(this::toAttributeRequest).toList(),
                view.lockVersion());
    }

    private StageRequest toStageRequest(StageView s) {
        return new StageRequest(s.key(), s.name(), s.responsibleDepartmentId(), s.requiresApproval(),
                s.autoAdvance(), s.portalVisible(), s.slaDays(), s.writeScope(),
                s.notificationTemplateKey(), toConditionRequest(s.entryCondition()),
                s.fallbackNextStageKey(),
                s.milestones().stream().map(this::toMilestoneRequest).toList(),
                s.branchRules().stream().map(this::toBranchRuleRequest).toList());
    }

    private MilestoneRequest toMilestoneRequest(MilestoneView m) {
        return new MilestoneRequest(m.key(), m.name(), m.description(), m.estimatedDurationDays(),
                m.dependsOnMilestoneKeys(),
                m.requirements().stream().map(this::toRequirementRequest).toList());
    }

    private RequirementRequest toRequirementRequest(RequirementView r) {
        return new RequirementRequest(r.kind(), r.label(), r.weight(), r.mandatory(),
                r.documentCategory(), r.approverRelationship());
    }

    private BranchRuleRequest toBranchRuleRequest(BranchRuleView b) {
        return new BranchRuleRequest(toConditionRequest(b.condition()), b.targetStageKey());
    }

    private AttributeRequest toAttributeRequest(AttributeView a) {
        return new AttributeRequest(a.key(), a.label(), a.dataType(), a.required(), a.allowedValues());
    }

    private ConditionRequest toConditionRequest(ConditionView c) {
        if (c == null) return null;
        return new ConditionRequest(c.source(), c.key(), c.operator(), c.value(), c.values());
    }
}
