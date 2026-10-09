package co.ara.onboarding.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The open, dated work the notification sweep reminds about (6B spec 6.2): RLS-bound SQL run in the
 * sweep's tenant transaction (plan amendment 4). Never returned to a caller outside the sweep; every
 * notification written from these rows is gated per recipient by RecipientAccess in the pipeline.
 * A held case (ON_HOLD) is not reminded about: its clock is paused and its due dates move on resume.
 */
@Component
public class DeadlineCandidates {

    public record Dated(UUID id, UUID caseId, UUID ownerUserId, LocalDate date, String label) {}

    private static final RowMapper<Dated> DATED = (rs, i) -> new Dated(rs.getObject(1, UUID.class),
            rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, LocalDate.class), rs.getString(5));

    private final JdbcTemplate jdbc;

    DeadlineCandidates(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Owner = the assignee. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Dated> openTasksDueBefore(LocalDate today) {
        return jdbc.query("""
                SELECT id, case_id, assignee_id, due_date, title FROM task
                 WHERE status NOT IN ('COMPLETED','CANCELLED') AND due_date < ? AND assignee_id IS NOT NULL""",
                DATED, today);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Dated> openTasksDueOnOrAfter(LocalDate today) {
        return jdbc.query("""
                SELECT t.id, t.case_id, t.assignee_id, t.due_date, t.title FROM task t
                  JOIN onboarding_case c ON c.id = t.case_id
                 WHERE t.status NOT IN ('COMPLETED','CANCELLED') AND t.due_date >= ? AND t.assignee_id IS NOT NULL
                   AND c.status = 'ACTIVE'""", DATED, today);
    }

    /** Owner = the milestone's owner; the label is its definition's name. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Dated> openMilestonesDueOnOrAfter(LocalDate today) {
        return jdbc.query("""
                SELECT m.id, m.case_id, m.owner_user_id, m.due_date, d.name
                  FROM milestone m JOIN milestone_definition d ON d.id = m.milestone_definition_id
                  JOIN onboarding_case c ON c.id = m.case_id
                 WHERE m.status IN ('PENDING','ACTIVE','BLOCKED') AND m.due_date >= ? AND m.owner_user_id IS NOT NULL
                   AND c.status = 'ACTIVE'""", DATED, today);
    }

    /** Columns id, case_id, requested_by, due_at, category; the caller converts due_at in the tenant zone. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Map<String, Object>> openRequestsDue() {
        return jdbc.queryForList("""
                SELECT r.id, r.case_id, r.requested_by, r.due_at, r.category FROM document_request r
                  JOIN onboarding_case c ON c.id = r.case_id
                 WHERE r.status = 'OPEN' AND r.due_at IS NOT NULL AND c.status = 'ACTIVE'""");
    }

    /**
     * Live (non-retired) documents with an expiry on an ACTIVE case. Columns id, case_id, owner_user_id
     * (the case owner), expires_at, name; the caller converts expires_at in the tenant zone. A document
     * belongs to a case (case_id is NOT NULL), and a case with no owner has nobody to remind, so it is skipped.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Map<String, Object>> liveDocumentsExpiring() {
        return jdbc.queryForList("""
                SELECT d.id, d.case_id, c.owner_user_id, d.expires_at, d.name FROM document d
                  JOIN onboarding_case c ON c.id = d.case_id
                 WHERE d.status <> 'RETIRED' AND d.expires_at IS NOT NULL AND c.owner_user_id IS NOT NULL
                   AND c.status = 'ACTIVE'""");
    }

    /**
     * Non-cancelled agreements carrying an expiry or renewal date, on an ACTIVE case. EXPIRED is derived on
     * read and never stored, so "not cancelled" is the whole liveness test here; the sweep skips past dates.
     * Columns id, case_id, owner_user_id, name, expires_at, renewal_date (dates), notice_period_days.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Map<String, Object>> liveAgreementsWithDates() {
        return jdbc.queryForList("""
                SELECT a.id, a.case_id, a.owner_user_id, a.name, a.expires_at, a.renewal_date, a.notice_period_days
                  FROM agreement a JOIN onboarding_case c ON c.id = a.case_id
                 WHERE a.status <> 'CANCELLED' AND (a.expires_at IS NOT NULL OR a.renewal_date IS NOT NULL)
                   AND c.status = 'ACTIVE'""");
    }
}
