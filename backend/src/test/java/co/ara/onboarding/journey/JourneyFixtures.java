package co.ara.onboarding.journey;

import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.AttributeType;
import co.ara.onboarding.workflow.MilestoneDefinition;
import co.ara.onboarding.workflow.MilestoneDefinitionRepository;
import co.ara.onboarding.workflow.PublishService;
import co.ara.onboarding.workflow.RequirementDefinition;
import co.ara.onboarding.workflow.RequirementDefinitionRepository;
import co.ara.onboarding.workflow.RequirementKind;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import co.ara.onboarding.workflow.TemplateStatus;
import co.ara.onboarding.workflow.VersionStatus;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.AttributeRequest;
import co.ara.onboarding.workflow.WorkflowService;
import co.ara.onboarding.workflow.WorkflowTemplate;
import co.ara.onboarding.workflow.WorkflowTemplateRepository;
import co.ara.onboarding.workflow.WorkflowVersion;
import co.ara.onboarding.workflow.WorkflowVersionRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;

/**
 * Case-creation boilerplate shared by JourneyScopingTest's five descriptor tests
 * (and reusable by later tasks), extracted so it lives in exactly one place rather
 * than being copy-pasted per test the way JourneyPersistenceTest's private newCase
 * would otherwise have been. Every method must be called inside
 * {@link TenantFixture#runAs} or {@link TenantFixture#runAsUser} -- every table
 * here is RLS-protected.
 */
@Component
public class JourneyFixtures {

    private final CaseRepository cases;
    private final CaseParticipantRepository participants;
    private final MilestoneRepository milestones;
    private final RequirementRepository requirements;
    private final ApprovalRepository approvals;
    private final WorkflowTemplateRepository templates;
    private final WorkflowVersionRepository versions;
    private final StageRepository stages;
    private final MilestoneDefinitionRepository milestoneDefinitions;
    private final RequirementDefinitionRepository requirementDefinitions;
    private final TenantFixture tenantFixture;
    private final WorkflowService workflows;
    private final PublishService publishService;
    private final AtomicInteger stageOrdinal = new AtomicInteger();

    public JourneyFixtures(CaseRepository cases, CaseParticipantRepository participants,
                           MilestoneRepository milestones, RequirementRepository requirements,
                           ApprovalRepository approvals, WorkflowTemplateRepository templates,
                           WorkflowVersionRepository versions, StageRepository stages,
                           MilestoneDefinitionRepository milestoneDefinitions,
                           RequirementDefinitionRepository requirementDefinitions,
                           TenantFixture tenantFixture, WorkflowService workflows,
                           PublishService publishService) {
        this.cases = cases;
        this.participants = participants;
        this.milestones = milestones;
        this.requirements = requirements;
        this.approvals = approvals;
        this.templates = templates;
        this.versions = versions;
        this.stages = stages;
        this.milestoneDefinitions = milestoneDefinitions;
        this.requirementDefinitions = requirementDefinitions;
        this.tenantFixture = tenantFixture;
        this.workflows = workflows;
        this.publishService = publishService;
    }

    /** A minimal, validly-pinned case with no ownership set. */
    public Case newCase(UUID tenant) {
        return newCase(tenant, null, null, null);
    }

    /**
     * A validly-pinned case carrying explicit ownership columns, for exercising
     * DEPARTMENT, TEAM and ASSIGNED scope. Any of the three may be null. The
     * customer underneath carries no ownership of its own -- CaseDescriptor
     * resolves scope from the case's own copied columns, never the customer's.
     */
    public Case newCase(UUID tenant, UUID ownerUserId, UUID departmentId, UUID teamId) {
        UUID customerId = tenantFixture.createCustomer(
                tenant, "Case Customer " + Uuid7.generate(), null, null, null);

        WorkflowTemplate t = new WorkflowTemplate();
        t.setId(Uuid7.generate());
        t.setTenantId(tenant);
        t.setName("Journey Fixture Template " + Uuid7.generate());
        t.setStatus(TemplateStatus.ACTIVE);
        templates.save(t);

        WorkflowVersion v = new WorkflowVersion();
        v.setId(Uuid7.generate());
        v.setTenantId(tenant);
        v.setTemplateId(t.getId());
        v.setVersionNo(1);
        v.setStatus(VersionStatus.DRAFT);
        versions.save(v);

        Case c = new Case();
        c.setId(Uuid7.generate());
        c.setTenantId(tenant);
        c.setName("Journey Fixture Case " + Uuid7.generate());
        c.setCustomerId(customerId);
        c.setTemplateId(t.getId());
        c.setVersionId(v.getId());
        c.setStatus(CaseStatus.ACTIVE);
        c.setStartedAt(Instant.now());
        c.setOwnerUserId(ownerUserId);
        c.setOwningDepartmentId(departmentId);
        c.setOwningTeamId(teamId);
        return cases.saveAndFlush(c);
    }

    /**
     * A stage on the given version, for use as an approval's stage_id.
     *
     * The ordinal comes from a monotonic counter, not a literal 1: stage carries
     * UNIQUE (version_id, ordinal), and a test that creates a milestone (which
     * makes its own stage) and then calls this directly against the same version
     * -- childrenResolveThroughTheirCase does exactly that -- would otherwise
     * collide on ordinal 1.
     */
    public UUID newStage(UUID tenant, UUID versionId) {
        Stage stage = new Stage();
        stage.setId(Uuid7.generate());
        stage.setTenantId(tenant);
        stage.setVersionId(versionId);
        stage.setOrdinal(stageOrdinal.incrementAndGet());
        stage.setName("Fixture Stage " + Uuid7.generate());
        return stages.saveAndFlush(stage).getId();
    }

    /** A PENDING milestone on the given case, with its own fresh stage and milestone_definition. */
    public Milestone newMilestone(UUID tenant, Case c) {
        UUID stageId = newStage(tenant, c.getVersionId());

        MilestoneDefinition definition = new MilestoneDefinition();
        definition.setId(Uuid7.generate());
        definition.setTenantId(tenant);
        definition.setVersionId(c.getVersionId());
        definition.setStageId(stageId);
        definition.setOrdinal(1);
        definition.setName("Fixture Milestone " + Uuid7.generate());
        definition.setEstimatedDurationDays(1);
        milestoneDefinitions.save(definition);

        Milestone m = new Milestone();
        m.setId(Uuid7.generate());
        m.setTenantId(tenant);
        m.setCaseId(c.getId());
        m.setMilestoneDefinitionId(definition.getId());
        m.setStatus(MilestoneStatus.PENDING);
        return milestones.saveAndFlush(m);
    }

    /** An OPEN requirement on the given milestone, with its own fresh requirement_definition. */
    public Requirement newRequirement(UUID tenant, Case c, Milestone m) {
        RequirementDefinition definition = new RequirementDefinition();
        definition.setId(Uuid7.generate());
        definition.setTenantId(tenant);
        definition.setVersionId(c.getVersionId());
        definition.setMilestoneDefinitionId(m.getMilestoneDefinitionId());
        definition.setOrdinal(1);
        definition.setKind(RequirementKind.MANUAL);
        definition.setLabel("Fixture Requirement " + Uuid7.generate());
        requirementDefinitions.save(definition);

        Requirement r = new Requirement();
        r.setId(Uuid7.generate());
        r.setTenantId(tenant);
        r.setCaseId(c.getId());
        r.setMilestoneId(m.getId());
        r.setRequirementDefinitionId(definition.getId());
        r.setStatus(RequirementStatus.OPEN);
        return requirements.saveAndFlush(r);
    }

    /** A PENDING STAGE_EXIT approval on the given case. */
    public Approval newApproval(UUID tenant, UUID caseId, UUID stageId, UUID requestedBy) {
        Approval a = new Approval();
        a.setId(Uuid7.generate());
        a.setTenantId(tenant);
        a.setCaseId(caseId);
        a.setKind(ApprovalKind.STAGE_EXIT);
        a.setStageId(stageId);
        a.setRequestedBy(requestedBy);
        a.setRequestedAt(Instant.now());
        a.setReason("Fixture approval");
        a.setStatus(ApprovalStatus.PENDING);
        return approvals.saveAndFlush(a);
    }

    /** Adds a case_participant row. */
    public UUID addParticipant(UUID tenant, UUID caseId, UUID userId,
                               RelationshipType relationship, ParticipantStatus status) {
        CaseParticipant p = new CaseParticipant();
        p.setId(Uuid7.generate());
        p.setTenantId(tenant);
        p.setCaseId(caseId);
        p.setUserId(userId);
        p.setRelationship(relationship);
        p.setStatus(status);
        return participants.saveAndFlush(p).getId();
    }

    // ---- published-workflow builders (Task 13, reusable by Tasks 15/18/19/26/27) ----
    //
    // These call the live WorkflowService/PublishService rather than writing
    // Stage/MilestoneDefinition/RequirementDefinition rows directly, so every
    // fixture case is pinned to a version that genuinely passed PublishService's
    // five validations -- the same graphs CaseEngine will actually be asked to run.

    /**
     * Three stages of two milestones each (durations 2 then 3 business days), one
     * MANUAL requirement per milestone, no declared attributes. What
     * CaseCreationTest.creatingACaseInstantiatesEveryMilestoneAndRequirement pins a
     * case to. Returns the published version id.
     */
    public UUID publishedThreeStageWorkflow() {
        UUID templateId = workflows.createTemplate("Fixture Three-Stage " + Uuid7.generate(), "").id();
        UUID draftId = workflows.createDraft(templateId);

        List<WorkflowDefinitionRequest.StageRequest> stageRequests = List.of(
                threeStageEntry("s1", "Stage One"),
                threeStageEntry("s2", "Stage Two"),
                threeStageEntry("s3", "Stage Three"));
        workflows.replaceDraft(draftId, new WorkflowDefinitionRequest(stageRequests, List.of(), 0L));
        publishService.publish(draftId);
        return draftId;
    }

    private WorkflowDefinitionRequest.StageRequest threeStageEntry(String key, String name) {
        return stage(key, name, List.of(
                milestone(key + "-m1", name + " Milestone One", 2, List.of(), List.of(manual("Do it"))),
                milestone(key + "-m2", name + " Milestone Two", 3, List.of(), List.of(manual("Do it")))));
    }

    /** A single stage, single milestone, single requirement, no declared attributes. Already published. */
    public UUID publishedTemplate() {
        UUID templateId = workflows.createTemplate("Fixture Simple " + Uuid7.generate(), "").id();
        UUID draftId = workflows.createDraft(templateId);
        workflows.replaceDraft(draftId, new WorkflowDefinitionRequest(
                List.of(stage("s1", "Stage One", List.of(
                        milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))))),
                List.of(), 0L));
        publishService.publish(draftId);
        return templateId;
    }

    /**
     * A single stage/milestone/requirement, plus a required ENUM "segment"
     * attribute (allowed values ENTERPRISE, SMB) and an optional NUMBER
     * "employeeCount" attribute -- for CaseCreationTest's attribute-validation
     * scenarios. Already published. Returns the template id.
     */
    public UUID publishedTemplateWithSegmentAttribute() {
        UUID templateId = workflows.createTemplate("Fixture Attributed " + Uuid7.generate(), "").id();
        UUID draftId = workflows.createDraft(templateId);
        List<AttributeRequest> attributes = List.of(
                new AttributeRequest("segment", "Segment", AttributeType.ENUM, true,
                        List.of("ENTERPRISE", "SMB")),
                new AttributeRequest("employeeCount", "Employee Count", AttributeType.NUMBER, false, null));
        workflows.replaceDraft(draftId, new WorkflowDefinitionRequest(
                List.of(stage("s1", "Stage One", List.of(
                        milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))))),
                attributes, 0L));
        publishService.publish(draftId);
        return templateId;
    }

    /** A template with a draft that is never published, for the "cannot start a case" path. */
    public UUID draftOnlyTemplate() {
        UUID templateId = workflows.createTemplate("Fixture Draft Only " + Uuid7.generate(), "").id();
        UUID draftId = workflows.createDraft(templateId);
        workflows.replaceDraft(draftId, new WorkflowDefinitionRequest(
                List.of(stage("s1", "Stage One", List.of(
                        milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))))),
                List.of(), 0L));
        return templateId;
    }

    /** The template a published version belongs to. */
    public UUID templateOf(UUID versionId) {
        return versions.findById(versionId).orElseThrow().getTemplateId();
    }

    // ---- arbitrary graphs (Task 15, reusable by 18/19) -------------------------
    //
    // TransitionTest needs stage shapes (auto_advance=false, requires_approval,
    // entry conditions, branch rules) publishedThreeStageWorkflow/publishedTemplate
    // don't cover and that don't recur often enough to earn their own named
    // method. Building the request is cheap with WorkflowFixtures' builders plus
    // the StageRequest/BranchRuleRequest/ConditionRequest records' own public
    // constructors; only the create-draft-replace-publish plumbing is worth
    // sharing.

    /**
     * Creates a template, drafts, saves and publishes an arbitrary request. Returns
     * the versionId.
     *
     * There is deliberately no stageByKey lookup here: WorkflowDefinitionView.key is
     * echoed back as the persisted stage's own id in string form (see that record's
     * javadoc), not the client-local key the request declared -- "s1" never survives
     * a round trip. A test that needs a stage's real id reads it from
     * CaseService.roadmap() once a case exists, which carries genuine Stage ids
     * ordered by ordinal -- the same order the request declared its stages in.
     */
    public UUID publish(WorkflowDefinitionRequest request) {
        UUID templateId = workflows.createTemplate("Fixture " + Uuid7.generate(), "").id();
        UUID draftId = workflows.createDraft(templateId);
        workflows.replaceDraft(draftId, request);
        publishService.publish(draftId);
        return draftId;
    }

    /**
     * A second (or later) published version on an EXISTING template, for
     * MigrationTest -- createDraft(templateId) on a template that already has a
     * published version deep-copies it, and replaceDraft then overwrites that copy
     * wholesale with the given request, so the two versions can differ freely.
     *
     * The deep-copy path inside createDraft already calls replaceDraft once,
     * bumping the fresh draft's lockVersion from 0 to 1 -- request.lockVersion() is
     * therefore ignored and the draft's actual current value substituted, or this
     * fixture's own replaceDraft call would fail as a stale write.
     */
    public UUID publishNewVersion(UUID templateId, WorkflowDefinitionRequest request) {
        UUID draftId = workflows.createDraft(templateId);
        long currentLockVersion = versions.findById(draftId).orElseThrow().getLockVersion();
        workflows.replaceDraft(draftId, new WorkflowDefinitionRequest(
                request.stages(), request.attributes(), currentLockVersion));
        publishService.publish(draftId);
        return draftId;
    }
}
