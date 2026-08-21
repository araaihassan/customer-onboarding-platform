package co.ara.onboarding.journey;

import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.MilestoneDefinition;
import co.ara.onboarding.workflow.MilestoneDefinitionRepository;
import co.ara.onboarding.workflow.RequirementDefinition;
import co.ara.onboarding.workflow.RequirementDefinitionRepository;
import co.ara.onboarding.workflow.RequirementKind;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import co.ara.onboarding.workflow.TemplateStatus;
import co.ara.onboarding.workflow.VersionStatus;
import co.ara.onboarding.workflow.WorkflowTemplate;
import co.ara.onboarding.workflow.WorkflowTemplateRepository;
import co.ara.onboarding.workflow.WorkflowVersion;
import co.ara.onboarding.workflow.WorkflowVersionRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final AtomicInteger stageOrdinal = new AtomicInteger();

    public JourneyFixtures(CaseRepository cases, CaseParticipantRepository participants,
                           MilestoneRepository milestones, RequirementRepository requirements,
                           ApprovalRepository approvals, WorkflowTemplateRepository templates,
                           WorkflowVersionRepository versions, StageRepository stages,
                           MilestoneDefinitionRepository milestoneDefinitions,
                           RequirementDefinitionRepository requirementDefinitions,
                           TenantFixture tenantFixture) {
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
}
