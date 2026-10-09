package co.ara.onboarding.notification;

import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
 *   <li>{@link #milestone} -- a milestone's definition name, case, owner and due date.</li>
 *   <li>{@link #document} / {@link #documentRequest} -- a document's name, case, uploader and expiry;
 *       a request's case, category, description and requester.</li>
 *   <li>{@link #requesterOfDocument} / {@link #internalVersionUploader} -- who asked for the document,
 *       and a version's uploader when INTERNAL.</li>
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

    public record MilestoneFacts(UUID id, String name, UUID caseId, UUID ownerUserId, LocalDate dueDate) {}

    public record DocumentFacts(UUID id, String name, UUID caseId, UUID uploadedBy, Instant expiresAt) {}

    public record AgreementFacts(UUID id, String name, UUID caseId, UUID ownerUserId, String status,
                                 LocalDate expiresAt, LocalDate renewalDate, Integer noticePeriodDays) {}

    public record RequestFacts(UUID id, UUID caseId, String category, String description, UUID requestedBy) {}

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

    /** The name comes from the milestone's definition; the instance carries only its id. */
    @Transactional(propagation = Propagation.MANDATORY)
    public MilestoneFacts milestone(UUID milestoneId) {
        return jdbc.queryForObject("""
                SELECT m.id, d.name, m.case_id, m.owner_user_id, m.due_date
                  FROM milestone m JOIN milestone_definition d ON d.id = m.milestone_definition_id WHERE m.id = ?""",
                (rs, i) -> new MilestoneFacts(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getObject(5, LocalDate.class)), milestoneId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentFacts document(UUID documentId) {
        return jdbc.queryForObject("SELECT id, name, case_id, uploaded_by, expires_at FROM document WHERE id = ?",
                (rs, i) -> new DocumentFacts(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()),
                documentId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RequestFacts documentRequest(UUID requestId) {
        return jdbc.queryForObject(
                "SELECT id, case_id, category, description, requested_by FROM document_request WHERE id = ?",
                (rs, i) -> new RequestFacts(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getObject(5, UUID.class)), requestId);
    }

    /** The requester of the (latest) request this document fulfilled, if any. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> requesterOfDocument(UUID documentId) {
        return jdbc.queryForList("""
                SELECT requested_by FROM document_request WHERE fulfilled_document_id = ?
                 ORDER BY requested_at DESC LIMIT 1""", UUID.class, documentId).stream().findFirst();
    }

    /** A version's uploader, only when that uploader is an INTERNAL user (never a portal contact). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> internalVersionUploader(UUID documentId, int versionNo) {
        return jdbc.queryForList("""
                SELECT v.uploaded_by FROM document_version v JOIN app_user u ON u.id = v.uploaded_by
                 WHERE v.document_id = ? AND v.version_no = ? AND u.user_type = 'INTERNAL'""",
                UUID.class, documentId, versionNo).stream().findFirst();
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

    public record OutdatedCase(UUID caseId, UUID ownerUserId, UUID customerId) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public List<OutdatedCase> openCasesOnEarlierVersions(UUID templateId, UUID publishedVersionId) {
        return jdbc.query("""
                SELECT id, owner_user_id, customer_id FROM onboarding_case
                 WHERE template_id = ? AND version_id <> ? AND status IN ('ACTIVE','ON_HOLD') AND owner_user_id IS NOT NULL
                 ORDER BY id""",
                (rs, i) -> new OutdatedCase(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)),
                templateId, publishedVersionId);
    }

    public record CustomerFacts(UUID id, String displayName, UUID ownerUserId) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public CustomerFacts customer(UUID customerId) {
        return jdbc.queryForObject("SELECT id, display_name, owner_user_id FROM customer WHERE id = ?",
                (rs, i) -> new CustomerFacts(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class)),
                customerId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String templateName(UUID templateId) {
        return jdbc.queryForObject("SELECT name FROM workflow_template WHERE id = ?", String.class, templateId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AgreementFacts agreement(UUID agreementId) {
        return jdbc.queryForObject("""
                SELECT id, name, case_id, owner_user_id, status, expires_at, renewal_date, notice_period_days
                  FROM agreement WHERE id = ?""",
                (rs, i) -> new AgreementFacts(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getString(5), rs.getObject(6, LocalDate.class),
                        rs.getObject(7, LocalDate.class), (Integer) rs.getObject(8)), agreementId);
    }
}
