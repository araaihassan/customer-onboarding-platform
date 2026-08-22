package co.ara.onboarding.workflow;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowPersistenceTest extends PostgresTestBase {

    @Autowired WorkflowTemplateRepository templates;
    @Autowired WorkflowVersionRepository versions;
    @Autowired StageRepository stages;
    @Autowired TenantFixture fixture;
    @Autowired JdbcTemplate jdbc;   // the onboarding_app connection

    @Test
    void aDraftVersionAndItsStagesCanBeWrittenAndRead() {
        UUID tenant = fixture.createTenant("wf-write");
        fixture.runAs(tenant, () -> {
            WorkflowTemplate t = newTemplate(tenant, "Standard");
            templates.save(t);
            WorkflowVersion v = newDraft(tenant, t, 1);
            versions.save(v);
            stages.save(newStage(tenant, v, 1, "Registration"));

            assertThat(stages.findByVersionIdOrderByOrdinal(v.getId()))
                    .extracting(Stage::getName).containsExactly("Registration");
        });
    }

    /**
     * The frozen-workflow promise, at the storage layer. A published version the
     * application can still rewrite is frozen by convention, and every case pinned to
     * it is trusting that convention.
     */
    @Test
    void aPublishedVersionCannotBeUpdatedOrDeleted() {
        UUID tenant = fixture.createTenant("wf-frozen");
        var versionId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            WorkflowTemplate t = newTemplate(tenant, "Frozen");
            templates.save(t);
            WorkflowVersion v = newDraft(tenant, t, 1);
            versions.save(v);
            stages.save(newStage(tenant, v, 1, "Registration"));
            versionId.set(v.getId());
        });

        // Publish by the only route that may: a DRAFT -> PUBLISHED update.
        fixture.runAs(tenant, () -> jdbc.update(
                "UPDATE workflow_version SET status = 'PUBLISHED' WHERE id = ?", versionId.get()));

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> jdbc.update(
                "UPDATE workflow_version SET version_no = 99 WHERE id = ?", versionId.get())))
                .hasMessageContaining("published");

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> jdbc.update(
                "DELETE FROM stage WHERE version_id = ?", versionId.get())))
                .hasMessageContaining("published");

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> jdbc.update(
                "UPDATE stage SET name = 'Renamed' WHERE version_id = ?", versionId.get())))
                .hasMessageContaining("published");
    }

    @Test
    void aDraftsStagesCanBeDeleted() {
        UUID tenant = fixture.createTenant("wf-draft-delete");
        fixture.runAs(tenant, () -> {
            WorkflowTemplate t = newTemplate(tenant, "Editable");
            templates.save(t);
            WorkflowVersion v = newDraft(tenant, t, 1);
            versions.save(v);
            Stage s = newStage(tenant, v, 1, "Registration");
            stages.save(s);

            stages.delete(s);
            assertThat(stages.findByVersionIdOrderByOrdinal(v.getId())).isEmpty();
        });
    }

    /**
     * workflow_template is a business record -- deactivated, never deleted -- so the
     * schema-wide DELETE denial still applies to it. The seven definition tables carry
     * an explicit grant; this one must not.
     *
     * hasStackTraceContaining, matching AppRoleTest and CustomerPersistenceTest: 42501
     * (insufficient privilege) falls in SQLState class "42", which Spring's fallback
     * SQLStateSQLExceptionTranslator maps wholesale to BadSqlGrammarException, whose
     * own message says only "bad SQL grammar" -- "permission denied" lives in the
     * wrapped cause. The plan's brief asserted hasMessageContaining here; amended to
     * the pattern this codebase already uses for the identical failure shape.
     */
    @Test
    void aTemplateCannotBeDeleted() {
        UUID tenant = fixture.createTenant("wf-template-delete");
        var templateId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            WorkflowTemplate t = newTemplate(tenant, "Permanent");
            templates.save(t);
            templateId.set(t.getId());
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> jdbc.update(
                "DELETE FROM workflow_template WHERE id = ?", templateId.get())))
                .hasStackTraceContaining("permission denied");
    }

    @Test
    void twoDraftsForOneTemplateAreRefused() {
        UUID tenant = fixture.createTenant("wf-one-draft");
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            WorkflowTemplate t = newTemplate(tenant, "Single");
            templates.save(t);
            versions.save(newDraft(tenant, t, 1));
            versions.saveAndFlush(newDraft(tenant, t, 2));
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    private WorkflowTemplate newTemplate(UUID tenant, String name) {
        WorkflowTemplate t = new WorkflowTemplate();
        t.setId(Uuid7.generate());
        t.setTenantId(tenant);
        t.setName(name);
        t.setStatus(TemplateStatus.ACTIVE);
        return t;
    }

    private WorkflowVersion newDraft(UUID tenant, WorkflowTemplate template, int versionNo) {
        WorkflowVersion v = new WorkflowVersion();
        v.setId(Uuid7.generate());
        v.setTenantId(tenant);
        v.setTemplateId(template.getId());
        v.setVersionNo(versionNo);
        v.setStatus(VersionStatus.DRAFT);
        return v;
    }

    private Stage newStage(UUID tenant, WorkflowVersion version, int ordinal, String name) {
        Stage s = new Stage();
        s.setId(Uuid7.generate());
        s.setTenantId(tenant);
        s.setVersionId(version.getId());
        s.setOrdinal(ordinal);
        s.setName(name);
        return s;
    }
}
