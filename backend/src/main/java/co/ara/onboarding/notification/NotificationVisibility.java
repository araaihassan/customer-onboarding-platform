package co.ara.onboarding.notification;

import co.ara.onboarding.agreement.Agreement;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RecipientAccess;
import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.task.Task;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Whether a stored notification is still about something its recipient may see NOW -- the one
 * subject-type to permission mapping both the digest (before it emails a pending row) and the inbox
 * (before it shows or counts one) apply, so a revoked grant, a lost team or department, a removed
 * participant or a retargeted document takes effect on the next call (CLAUDE.md: authority is never
 * cached across requests), and the two readers can never disagree.
 *
 * <p>Never broader than the gate the pipeline applied when the row was written: the subject under
 * its own permission where it had one (task, document, agreement, customer), a comment on its
 * commented task (task.view) or on its journey (case.view) exactly as TaskNotifications gated it, a
 * workflow-published row on a case of that template the recipient owns and can view
 * (WorkflowNotifications gated on the first such case), and otherwise the case. A row with nothing
 * to check against fails closed. Every check is {@link RecipientAccess}'s, the pipeline's own gate.
 */
@Component
public class NotificationVisibility {

    private final RecipientAccess access;
    private final JdbcTemplate jdbc;

    NotificationVisibility(RecipientAccess access, JdbcTemplate jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean visibleNow(UUID userId, String subjectType, UUID subjectId, UUID caseId) {
        if (subjectType != null) {
            switch (subjectType) {
                case "task" -> { return access.canView(userId, PermissionKeys.TASK_VIEW, Task.class, subjectId); }
                case "document" -> { return access.canView(userId, PermissionKeys.DOCUMENT_VIEW, Document.class, subjectId); }
                case "agreement" -> { return access.canView(userId, PermissionKeys.AGREEMENT_VIEW, Agreement.class, subjectId); }
                case "customer" -> { return access.canView(userId, PermissionKeys.CUSTOMER_VIEW, Customer.class, subjectId); }
                case "comment" -> { return commentVisible(userId, subjectId); }
                case "workflow_version" -> { return workflowVersionVisible(userId, subjectId); }
                default -> { }
            }
        }
        return caseId != null && access.canView(userId, PermissionKeys.CASE_VIEW, Case.class, caseId);
    }

    private boolean commentVisible(UUID userId, UUID commentId) {
        if (commentId == null) return false;
        var rows = jdbc.queryForList("SELECT resource_type, resource_id, case_id FROM comment WHERE id = ?", commentId);
        if (rows.isEmpty()) return false;
        var c = rows.get(0);
        if ("task".equals(c.get("resource_type"))) {
            return access.canView(userId, PermissionKeys.TASK_VIEW, Task.class, (UUID) c.get("resource_id"));
        }
        return access.canView(userId, PermissionKeys.CASE_VIEW, Case.class, (UUID) c.get("case_id"));
    }

    private boolean workflowVersionVisible(UUID userId, UUID versionId) {
        if (versionId == null) return false;
        List<UUID> cases = jdbc.queryForList("""
                SELECT c.id FROM onboarding_case c JOIN workflow_version v ON v.template_id = c.template_id
                 WHERE v.id = ? AND c.owner_user_id = ? ORDER BY c.id""", UUID.class, versionId, userId);
        return cases.stream().anyMatch(id -> access.canView(userId, PermissionKeys.CASE_VIEW, Case.class, id));
    }
}
