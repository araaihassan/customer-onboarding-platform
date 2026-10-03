package co.ara.onboarding.sla;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Invariant 5: the database's unique key is what makes escalation idempotent, so this is plain SQL
 * with ON CONFLICT DO NOTHING ... RETURNING id -- an empty result means another sweep (or an
 * earlier run) already escalated this subject for this due date. A raised-and-caught unique
 * violation would instead abort the whole tenant transaction in Postgres.
 */
@Component
class EscalationWriter {
    private final JdbcTemplate jdbc;

    EscalationWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    Optional<UUID> insert(EscalationSubject type, UUID subjectId, UUID caseId, UUID latePersonId,
                          RecipientResolver.Resolution resolution, LocalDate dueDate, int overdueDays, Instant at) {
        Timestamp ts = Timestamp.from(at);
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO escalation (id, tenant_id, subject_type, subject_id, case_id, late_user_id, route,
                                        escalated_to_user_id, due_date_at_escalation, overdue_days, escalated_at,
                                        created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT escalation_once_per_subject_and_due_date DO NOTHING
                RETURNING id""", UUID.class,
                Uuid7.generate(), TenantContext.getRequired(), type.name(), subjectId, caseId, latePersonId,
                resolution.route().name(), resolution.primaryUserId(), dueDate, overdueDays, ts, ts, ts);
        return inserted.stream().findFirst();
    }
}
