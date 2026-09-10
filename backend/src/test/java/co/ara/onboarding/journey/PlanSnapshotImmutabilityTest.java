package co.ara.onboarding.journey;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 22: {@code plan_revision}/{@code plan_revision_item}, the second append-only
 * table in the codebase after {@code audit_event} -- audit_event's argument, applied
 * to a second table: a snapshot the application can rewrite is not a snapshot. "What
 * did we send on 15 October?" has to have an answer, for governance packs and for
 * disputes.
 *
 * These tests write directly through JdbcTemplate rather than a repository/service --
 * neither exists yet (Tasks 24-25) -- exactly the shape JourneyPersistenceTest and
 * AuditAppendOnlyTest already use for schema-only proofs. Setup (the INSERTs) runs
 * inside {@link TenantFixture#runAs}, which binds the tenant and opens the
 * transaction that RLS's WITH CHECK needs; the failing UPDATE/DELETE assertions wrap
 * the WHOLE runAs call in assertThatThrownBy rather than asserting inside its lambda,
 * per this repository's own convention (runAs uses a rollback-only TransactionTemplate,
 * and asserting inside it masks the real exception with
 * UnexpectedRollbackException) -- and in any case the GRANT check that produces
 * "permission denied" is a role-level privilege check, independent of whether a
 * tenant is bound at all.
 *
 * hasStackTraceContaining, not hasMessageContaining as the plan's own pseudocode
 * has it: matching AuditAppendOnlyTest's and JourneyPersistenceTest's identical
 * amendment for the same failure shape -- JdbcTemplate wraps the driver's
 * "permission denied" text in a BadSqlGrammarException whose own message is
 * generic, so the Postgres text only appears on the wrapped cause.
 */
class PlanSnapshotImmutabilityTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aSnapshotItemCannotBeUpdated() {
        UUID tenant = fixture.createTenant("plan-snapshot-update");
        var itemId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            Milestone m = journey.newMilestone(tenant, c);
            UUID issuer = fixture.createUser(tenant, "issuer@fixture.test");
            UUID revisionId = insertRevision(tenant, c.getId(), 1, issuer);
            itemId.set(insertRevisionItem(tenant, revisionId, c, m));
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> jdbc.update(
                "UPDATE plan_revision_item SET due_date = ? WHERE id = ?",
                LocalDate.now(), itemId.get())))
                .hasStackTraceContaining("permission denied for table plan_revision_item");
    }

    @Test
    void aSnapshotItemCannotBeDeleted() {
        UUID tenant = fixture.createTenant("plan-snapshot-delete");
        var itemId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            Milestone m = journey.newMilestone(tenant, c);
            UUID issuer = fixture.createUser(tenant, "issuer@fixture.test");
            UUID revisionId = insertRevision(tenant, c.getId(), 1, issuer);
            itemId.set(insertRevisionItem(tenant, revisionId, c, m));
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () ->
                jdbc.update("DELETE FROM plan_revision_item WHERE id = ?", itemId.get())))
                .hasStackTraceContaining("permission denied for table plan_revision_item");
    }

    @Test
    void theRevisionItselfMayStillTransition() {
        UUID tenant = fixture.createTenant("plan-snapshot-transition");
        var revisionId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            UUID issuer = fixture.createUser(tenant, "issuer@fixture.test");
            revisionId.set(insertRevision(tenant, c.getId(), 1, issuer));
        });

        assertThatNoException().isThrownBy(() -> fixture.runAs(tenant, () ->
                jdbc.update("UPDATE plan_revision SET status = 'APPROVED' WHERE id = ?",
                        revisionId.get())));
    }

    @Test
    void revisionNumbersAreUniquePerCase() {
        UUID tenant = fixture.createTenant("plan-snapshot-unique");
        var caseId = new AtomicReference<UUID>();
        var issuerId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            caseId.set(c.getId());
            issuerId.set(fixture.createUser(tenant, "issuer@fixture.test"));
            insertRevision(tenant, c.getId(), 1, issuerId.get());
        });

        // The second insertRevision, not the whole test, is what must throw --
        // wrap the runAs call itself rather than asserting inside its lambda
        // (JourneyPersistenceTest.oneAttributeValuePerCaseAndDefinition's own
        // convention): a caught exception still leaves Postgres's own transaction
        // aborted, and asserting from outside runAs avoids relying on any cleanup
        // happening inside a lambda whose transaction is already doomed.
        assertThatThrownBy(() -> fixture.runAs(tenant, () ->
                insertRevision(tenant, caseId.get(), 1, issuerId.get())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertRevision(UUID tenant, UUID caseId, int revisionNumber, UUID issuedBy) {
        UUID id = Uuid7.generate();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("""
                INSERT INTO plan_revision
                    (id, tenant_id, case_id, revision_number, status, issued_at, issued_by,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ISSUED', ?, ?, ?, ?)
                """,
                id, tenant, caseId, revisionNumber, now, issuedBy, now, now);
        return id;
    }

    private UUID insertRevisionItem(UUID tenant, UUID revisionId, Case c, Milestone m) {
        UUID id = Uuid7.generate();
        jdbc.update("""
                INSERT INTO plan_revision_item
                    (id, tenant_id, plan_revision_id, case_id, milestone_id, milestone_definition_id,
                     stage_name, milestone_name, due_date, estimated_duration_days, portal_visible,
                     sort_order, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenant, revisionId, c.getId(), m.getId(), m.getMilestoneDefinitionId(),
                "Stage One", "Milestone One", LocalDate.of(2026, 10, 15), 1, true, 1,
                Timestamp.from(Instant.now(clock)));
        return id;
    }
}
