package co.ara.onboarding.workflow;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sub-project 3A, Task 19: schema-level proof for plan_shape_approval -- gate 1 of
 * QA Q22. Proves the table exists BECAUSE workflow_version_frozen refuses every
 * UPDATE to a non-DRAFT row (approval columns could never have lived on the version
 * itself), that resubmission after rejection is allowed (no unique index on
 * version_id -- the latest row is the current state), and that the table is
 * tenant-isolated and undeletable like every other business-record table.
 */
class PlanShapeSchemaTest extends PostgresTestBase {

    @Autowired WorkflowService workflows;
    @Autowired PublishService publisher;
    @Autowired TenantFixture fixture;
    @Autowired JdbcTemplate jdbc;

    private UUID tenant;
    private UUID templateId;
    private UUID customerId;
    private UUID submittedBy;

    @BeforeEach
    void seedTenantTemplateAndCustomer() {
        tenant = fixture.createTenant("plan-shape-schema-" + Uuid7.generate());
        fixture.runAs(tenant, () -> {
            templateId = workflows.createTemplate("Plan Shape Template", "").id();
            customerId = fixture.createCustomer(tenant, "Plan Shape Co", null, null, null);
            submittedBy = fixture.createUser(tenant, "submitter@plan-shape-schema.example");
        });
    }

    /**
     * Not a hypothetical: this is the constraint that forced plan_shape_approval to
     * be its own table rather than columns on workflow_version.
     */
    @Test
    void approvalColumnsCouldNotHaveLivedOnTheVersionItself() {
        UUID versionId = publishADraft();

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> jdbc.update(
                "UPDATE workflow_version SET updated_at = now() WHERE id = ?", versionId)))
                .hasMessageContaining("published and cannot be modified");
    }

    /**
     * Re-submission creates a NEW row; the latest row (by submitted_at DESC) is the
     * current state. There is no unique index on version_id, deliberately.
     */
    @Test
    void aVersionMayBeSubmittedMoreThanOnceAfterARejection() {
        UUID versionId = publishADraft();

        insertShapeApproval(versionId, "REJECTED");
        assertThatNoException().isThrownBy(() -> insertShapeApproval(versionId, "SUBMITTED"));
    }

    @Test
    void theTableIsTenantIsolatedAndUndeletable() {
        assertThat(rlsEnabledOn("plan_shape_approval")).isTrue();
        assertThat(privilegesFor("plan_shape_approval")).containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE");
    }

    // ---- helpers ------------------------------------------------------------

    private UUID publishADraft() {
        var draftIdRef = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID draftId = workflows.createDraft(templateId);
            workflows.replaceDraft(draftId, new WorkflowDefinitionRequest(
                    List.of(stage("s1", "Stage One", List.of(
                            milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))))),
                    List.of(), 0L));
            publisher.publish(draftId);
            draftIdRef[0] = draftId;
        });
        return draftIdRef[0];
    }

    /** Must run inside {@link TenantFixture#runAs} -- plan_shape_approval is RLS-protected. */
    private void insertShapeApproval(UUID versionId, String status) {
        boolean decided = !"SUBMITTED".equals(status);
        fixture.runAs(tenant, () -> jdbc.update("""
                INSERT INTO plan_shape_approval
                    (id, tenant_id, version_id, template_id, customer_id, status,
                     submitted_at, submitted_by, decided_at, decided_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, now(), ?, ?, ?, now(), now())
                """, Uuid7.generate(), tenant, versionId, templateId, customerId, status,
                submittedBy,
                decided ? Timestamp.from(Instant.now()) : null,
                decided ? submittedBy : null));
    }

    private boolean rlsEnabledOn(String table) {
        Boolean enabled = jdbc.queryForObject(
                "SELECT relrowsecurity FROM pg_class WHERE relname = ?", Boolean.class, table);
        Boolean forced = jdbc.queryForObject(
                "SELECT relforcerowsecurity FROM pg_class WHERE relname = ?", Boolean.class, table);
        return Boolean.TRUE.equals(enabled) && Boolean.TRUE.equals(forced);
    }

    private List<String> privilegesFor(String table) {
        return jdbc.queryForList("""
                SELECT privilege_type FROM information_schema.role_table_grants
                WHERE table_name = ? AND grantee = 'onboarding_app'
                """, String.class, table);
    }
}
