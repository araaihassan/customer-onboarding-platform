package co.ara.onboarding.journey;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.AttributeDefinition;
import co.ara.onboarding.workflow.AttributeDefinitionRepository;
import co.ara.onboarding.workflow.AttributeType;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JourneyPersistenceTest extends PostgresTestBase {

    @Autowired CaseRepository cases;
    @Autowired MilestoneRepository milestones;
    @Autowired RequirementRepository requirements;
    @Autowired CaseAttributeValueRepository attributeValues;
    @Autowired WorkflowTemplateRepository templates;
    @Autowired WorkflowVersionRepository versions;
    @Autowired StageRepository stages;
    @Autowired MilestoneDefinitionRepository milestoneDefinitions;
    @Autowired RequirementDefinitionRepository requirementDefinitions;
    @Autowired AttributeDefinitionRepository attributeDefinitions;
    @Autowired TenantFixture fixture;
    @Autowired JdbcTemplate jdbc;   // the onboarding_app connection

    /**
     * A case pins a version, a milestone pins a milestone_definition, and a
     * requirement pins a requirement_definition and a milestone -- the four FKs
     * that make the runtime side of the graph loadable. Written and read back
     * through the repositories Task 13 onward depends on: findByCaseIdOrderById
     * and findByMilestoneId.
     */
    @Test
    void aCaseAndItsMilestonesCanBeWrittenAndRead() {
        UUID tenant = fixture.createTenant("j-write");
        fixture.runAs(tenant, () -> {
            Case c = newCase(tenant);

            Stage stage = newStage(tenant, c.getVersionId(), 1, "Registration");
            stages.save(stage);
            MilestoneDefinition definition =
                    newMilestoneDefinition(tenant, c.getVersionId(), stage.getId(), "Collect ID");
            milestoneDefinitions.save(definition);
            RequirementDefinition requirementDefinition =
                    newRequirementDefinition(tenant, c.getVersionId(), definition.getId());
            requirementDefinitions.save(requirementDefinition);

            Milestone m = new Milestone();
            m.setId(Uuid7.generate());
            m.setTenantId(tenant);
            m.setCaseId(c.getId());
            m.setMilestoneDefinitionId(definition.getId());
            m.setStatus(MilestoneStatus.PENDING);
            milestones.save(m);

            Requirement r = new Requirement();
            r.setId(Uuid7.generate());
            r.setTenantId(tenant);
            r.setCaseId(c.getId());
            r.setMilestoneId(m.getId());
            r.setRequirementDefinitionId(requirementDefinition.getId());
            r.setStatus(RequirementStatus.OPEN);
            requirements.save(r);

            assertThat(milestones.findByCaseIdOrderById(c.getId()))
                    .extracting(Milestone::getMilestoneDefinitionId)
                    .containsExactly(definition.getId());
            assertThat(requirements.findByMilestoneId(m.getId()))
                    .extracting(Requirement::getStatus)
                    .containsExactly(RequirementStatus.OPEN);
        });
    }

    /**
     * Every journey table is a business record: DELETE stays denied, unlike the seven
     * definition tables of V12. A participant who left transitions to REMOVED; a
     * participation that vanished leaves an unexplained gap in the case's history.
     *
     * hasStackTraceContaining, not hasMessageContaining as the brief originally had
     * it: matching WorkflowPersistenceTest and CustomerPersistenceTest's own amendment
     * for the identical failure shape -- SQLState 42501 (insufficient privilege) falls
     * in class "42", which Spring's fallback SQLStateSQLExceptionTranslator maps
     * wholesale to BadSqlGrammarException, whose own message says only "bad SQL
     * grammar"; "permission denied" lives in the wrapped cause.
     */
    @Test
    void noJourneyTableCanBeDeletedFrom() {
        UUID tenant = fixture.createTenant("j-nodelete");
        var caseId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> caseId.set(newCase(tenant).getId()));

        for (String table : List.of("onboarding_case", "case_participant", "milestone",
                                    "requirement", "case_attribute_value", "approval")) {
            assertThatThrownBy(() -> fixture.runAs(tenant, () ->
                    jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenant)))
                    .as("DELETE on " + table)
                    .hasStackTraceContaining("permission denied");
        }
    }

    /**
     * The pinned version is NOT NULL. Invariant 2 of the spec's cross-check depends on
     * it: a case with no pinned version has no definition to execute, and every read
     * that joins one would silently return nothing.
     */
    @Test
    void aCaseCannotBeWrittenWithoutAPinnedVersion() {
        UUID tenant = fixture.createTenant("j-pinned");
        var customerRef = new AtomicReference<UUID>();
        var templateRef = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            customerRef.set(fixture.createCustomer(tenant, "Pinned Co", null, null, null));
            WorkflowTemplate t = newTemplate(tenant, "Pinned Template");
            templates.save(t);
            templateRef.set(t.getId());
        });
        UUID someCustomerId = customerRef.get();
        UUID someTemplateId = templateRef.get();

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> jdbc.update(
                "INSERT INTO onboarding_case (id, tenant_id, customer_id, template_id, "
              + "status, progress_percent, created_at, updated_at) "
              + "VALUES (?,?,?,?,'ACTIVE',0,now(),now())",
                Uuid7.generate(), tenant, someCustomerId, someTemplateId)))
                .hasMessageContaining("version_id");
    }

    /**
     * UNIQUE (case_id, attribute_definition_id): one value per attribute per case.
     * A second insert for the same pair -- even a different value -- is refused
     * rather than silently shadowing the first, which is what would let a branch
     * condition read a stale value.
     */
    @Test
    void oneAttributeValuePerCaseAndDefinition() {
        UUID tenant = fixture.createTenant("j-attr-unique");
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            Case c = newCase(tenant);

            AttributeDefinition attribute = new AttributeDefinition();
            attribute.setId(Uuid7.generate());
            attribute.setTenantId(tenant);
            attribute.setVersionId(c.getVersionId());
            attribute.setOrdinal(1);
            attribute.setKey("region");
            attribute.setLabel("Region");
            attribute.setDataType(AttributeType.STRING);
            attributeDefinitions.save(attribute);

            attributeValues.save(newAttributeValue(tenant, c, attribute, "EMEA"));
            attributeValues.saveAndFlush(newAttributeValue(tenant, c, attribute, "APAC"));
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- fixtures ----------------------------------------------------------

    /**
     * A minimal, validly-pinned case: a real customer, a persisted (not
     * necessarily published) template and version, satisfying onboarding_case's
     * three FKs and its NOT NULL version_id. Must be called inside
     * {@link TenantFixture#runAs}.
     */
    private Case newCase(UUID tenant) {
        UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);

        WorkflowTemplate t = newTemplate(tenant, "Standard " + Uuid7.generate());
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
        return cases.save(c);
    }

    private WorkflowTemplate newTemplate(UUID tenant, String name) {
        WorkflowTemplate t = new WorkflowTemplate();
        t.setId(Uuid7.generate());
        t.setTenantId(tenant);
        t.setName(name);
        t.setStatus(TemplateStatus.ACTIVE);
        return t;
    }

    private Stage newStage(UUID tenant, UUID versionId, int ordinal, String name) {
        Stage s = new Stage();
        s.setId(Uuid7.generate());
        s.setTenantId(tenant);
        s.setVersionId(versionId);
        s.setOrdinal(ordinal);
        s.setName(name);
        return s;
    }

    private MilestoneDefinition newMilestoneDefinition(UUID tenant, UUID versionId, UUID stageId, String name) {
        MilestoneDefinition d = new MilestoneDefinition();
        d.setId(Uuid7.generate());
        d.setTenantId(tenant);
        d.setVersionId(versionId);
        d.setStageId(stageId);
        d.setOrdinal(1);
        d.setName(name);
        d.setEstimatedDurationDays(1);
        return d;
    }

    private RequirementDefinition newRequirementDefinition(UUID tenant, UUID versionId, UUID milestoneDefinitionId) {
        RequirementDefinition r = new RequirementDefinition();
        r.setId(Uuid7.generate());
        r.setTenantId(tenant);
        r.setVersionId(versionId);
        r.setMilestoneDefinitionId(milestoneDefinitionId);
        r.setOrdinal(1);
        r.setKind(RequirementKind.MANUAL);
        r.setLabel("Do it");
        return r;
    }

    private CaseAttributeValue newAttributeValue(UUID tenant, Case c, AttributeDefinition attribute, String value) {
        CaseAttributeValue v = new CaseAttributeValue();
        v.setId(Uuid7.generate());
        v.setTenantId(tenant);
        v.setCaseId(c.getId());
        v.setAttributeDefinitionId(attribute.getId());
        v.setValueText(value);
        return v;
    }
}
