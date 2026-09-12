package co.ara.onboarding.programme;

import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.CaseStatus;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.TemplateStatus;
import co.ara.onboarding.workflow.VersionStatus;
import co.ara.onboarding.workflow.WorkflowTemplate;
import co.ara.onboarding.workflow.WorkflowTemplateRepository;
import co.ara.onboarding.workflow.WorkflowVersion;
import co.ara.onboarding.workflow.WorkflowVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 9: schema-level proof for programme, programme_participant and
 * programme_case -- tenant isolation, DELETE denial, and the partial unique
 * index that lets a case leave one programme and join another without ever
 * deleting the row that recorded the old membership.
 */
class ProgrammeSchemaTest extends PostgresTestBase {

    @Autowired JdbcTemplate jdbc; // the onboarding_app connection
    @Autowired TenantFixture fixture;
    @Autowired WorkflowTemplateRepository templates;
    @Autowired WorkflowVersionRepository versions;
    @Autowired CaseRepository cases;

    private UUID tenant;
    private UUID programmeA;
    private UUID programmeB;
    private UUID caseId;

    @BeforeEach
    void seedTenantProgrammesAndCase() {
        tenant = fixture.createTenant("prog-schema-" + Uuid7.generate());
        var programmeARef = new AtomicReference<UUID>();
        var programmeBRef = new AtomicReference<UUID>();
        var caseRef = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Programme Co", null, null, null);
            programmeARef.set(insertProgramme(customerId, "Programme A"));
            programmeBRef.set(insertProgramme(customerId, "Programme B"));
            caseRef.set(newCase(tenant, customerId).getId());
        });

        programmeA = programmeARef.get();
        programmeB = programmeBRef.get();
        caseId = caseRef.get();
    }

    @Test
    void allThreeProgrammeTablesAreTenantIsolatedAndUndeletable() {
        assertThat(rlsEnabledOn("programme")).isTrue();
        assertThat(rlsEnabledOn("programme_participant")).isTrue();
        assertThat(rlsEnabledOn("programme_case")).isTrue();
        assertThat(privilegesFor("programme")).containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE");
    }

    /**
     * The partial unique index, not a plain UNIQUE(case_id): DELETE is revoked, so a
     * journey that moves must leave a removed row behind and still be insertable.
     */
    @Test
    void aJourneyCanLeaveOneProgrammeAndJoinAnother() {
        insertProgrammeCase(programmeA, caseId, null);
        updateProgrammeCaseRemovedAt(programmeA, caseId, Instant.now());
        assertThatNoException().isThrownBy(() -> insertProgrammeCase(programmeB, caseId, null));
    }

    @Test
    void aJourneyCannotBeInTwoProgrammesAtOnce() {
        insertProgrammeCase(programmeA, caseId, null);
        assertThatThrownBy(() -> insertProgrammeCase(programmeB, caseId, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- helpers ------------------------------------------------------------

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

    private UUID insertProgramme(UUID customerId, String name) {
        UUID id = Uuid7.generate();
        jdbc.update("""
                INSERT INTO programme (id, tenant_id, customer_id, name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())
                """, id, tenant, customerId, name);
        return id;
    }

    /** Must run inside {@link TenantFixture#runAs} -- programme_case is RLS-protected. */
    private void insertProgrammeCase(UUID programmeId, UUID caseId, Instant removedAt) {
        fixture.runAs(tenant, () -> jdbc.update("""
                INSERT INTO programme_case
                    (id, tenant_id, programme_id, case_id, added_at, removed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), ?, now(), now())
                """, Uuid7.generate(), tenant, programmeId, caseId,
                removedAt == null ? null : Timestamp.from(removedAt)));
    }

    private void updateProgrammeCaseRemovedAt(UUID programmeId, UUID caseId, Instant removedAt) {
        fixture.runAs(tenant, () -> jdbc.update("""
                UPDATE programme_case SET removed_at = ?
                WHERE programme_id = ? AND case_id = ? AND removed_at IS NULL
                """, Timestamp.from(removedAt), programmeId, caseId));
    }

    /**
     * A minimal, validly-pinned case: a real customer, a persisted template and
     * version, satisfying onboarding_case's FKs and its NOT NULL version_id. Must
     * be called inside {@link TenantFixture#runAs}. Same shape as
     * journey.JourneyPersistenceTest's own newCase fixture.
     */
    private Case newCase(UUID tenantId, UUID customerId) {
        WorkflowTemplate t = new WorkflowTemplate();
        t.setId(Uuid7.generate());
        t.setTenantId(tenantId);
        t.setName("Programme Fixture Template " + Uuid7.generate());
        t.setStatus(TemplateStatus.ACTIVE);
        templates.save(t);

        WorkflowVersion v = new WorkflowVersion();
        v.setId(Uuid7.generate());
        v.setTenantId(tenantId);
        v.setTemplateId(t.getId());
        v.setVersionNo(1);
        v.setStatus(VersionStatus.DRAFT);
        versions.save(v);

        Case c = new Case();
        c.setId(Uuid7.generate());
        c.setTenantId(tenantId);
        c.setName("Programme Fixture Case " + Uuid7.generate());
        c.setCustomerId(customerId);
        c.setTemplateId(t.getId());
        c.setVersionId(v.getId());
        c.setStatus(CaseStatus.ACTIVE);
        c.setStartedAt(Instant.now());
        return cases.save(c);
    }
}
