package co.ara.onboarding.notification;

import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The facts a notification is composed from: RLS-bound SQL run in the caller's tenant
 * transaction (plan amendment 4).
 *
 * <p>Never returned to a caller. Every notification written from these facts is gated per
 * recipient by RecipientAccess.
 *
 * <ul>
 *   <li>{@link #tenantSlug} -- the bound tenant's slug, for link paths.</li>
 *   <li>{@link #caseFacts} -- a case's name, customer, owner and pinned template/version.</li>
 *   <li>{@link #task} -- a task's title, case, assignee and due date.</li>
 *   <li>{@link #earlierCommenters} / {@link #commentBody} -- a comment thread's earlier authors, and one body.</li>
 *   <li>{@link #caseAudience} -- the case owner plus every ACTIVE case participant.</li>
 *   <li>{@link #activeInternalEmail} -- an address only for an ACTIVE INTERNAL user.</li>
 *   <li>{@link #userName} -- a user's full name, or "Someone".</li>
 * </ul>
 */
@Component
public class SubjectFacts {

    public record CaseFacts(UUID id, String name, UUID customerId, String customerName, UUID ownerUserId,
                            UUID templateId, UUID versionId) {}

    public record TaskFacts(UUID id, String title, UUID caseId, UUID assigneeId, LocalDate dueDate) {}

    private final JdbcTemplate jdbc;

    SubjectFacts(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public String tenantSlug() {
        return jdbc.queryForObject("SELECT slug FROM tenant WHERE id = ?", String.class, TenantContext.getRequired());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CaseFacts caseFacts(UUID caseId) {
        return jdbc.queryForObject("""
                SELECT c.id, c.name, c.customer_id, cu.display_name, c.owner_user_id, c.template_id, c.version_id
                  FROM onboarding_case c JOIN customer cu ON cu.id = c.customer_id WHERE c.id = ?""",
                (rs, i) -> new CaseFacts(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class),
                        rs.getObject(7, UUID.class)), caseId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TaskFacts task(UUID taskId) {
        return jdbc.queryForObject("SELECT id, title, case_id, assignee_id, due_date FROM task WHERE id = ?",
                (rs, i) -> new TaskFacts(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getObject(5, LocalDate.class)), taskId);
    }

    /**
     * Distinct authors on the same thread as {@code commentId}, written at or before it. Compares
     * {@code resource_type} within the one table, so it is independent of the stored spelling.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> earlierCommenters(UUID commentId) {
        return jdbc.queryForList("""
                SELECT DISTINCT e.author_id FROM comment e JOIN comment c
                  ON c.resource_type = e.resource_type AND c.resource_id = e.resource_id
                 WHERE c.id = ? AND e.id <> c.id AND e.created_at <= c.created_at""", UUID.class, commentId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String commentBody(UUID commentId) {
        return jdbc.queryForObject("SELECT body FROM comment WHERE id = ?", String.class, commentId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Set<UUID> caseAudience(UUID caseId) {
        Set<UUID> audience = new LinkedHashSet<>();
        UUID owner = jdbc.queryForObject("SELECT owner_user_id FROM onboarding_case WHERE id = ?", UUID.class, caseId);
        if (owner != null) audience.add(owner);
        audience.addAll(jdbc.queryForList(
                "SELECT user_id FROM case_participant WHERE case_id = ? AND status = 'ACTIVE'", UUID.class, caseId));
        return audience;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> activeInternalEmail(UUID userId) {
        return jdbc.queryForList("SELECT email FROM app_user WHERE id = ? AND status = 'ACTIVE' AND user_type = 'INTERNAL'",
                String.class, userId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String userName(UUID userId) {
        if (userId == null) return "Someone";
        return jdbc.queryForList("SELECT full_name FROM app_user WHERE id = ?", String.class, userId)
                .stream().findFirst().orElse("Someone");
    }
}
