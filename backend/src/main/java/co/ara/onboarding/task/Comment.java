package co.ara.onboarding.task;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An internal comment against a {@link Task} or a journey (an {@code onboarding_case}
 * directly). caseId is NOT NULL on every comment -- including one on a task -- so
 * {@code CommentDescriptor} scopes on it exactly as {@code CaseDescriptor} does and
 * comment reads go through {@code AuthorizedQuery} with no carve-out. resourceType
 * plus resourceId is the polymorphic target; resourceType is gated twice, by
 * {@code comment_resource_type_ck} in SQL and by {@link CommentResourceType} in Java,
 * so a third kind needs a migration and a compile error, not just one of the two.
 */
@Entity
@Table(name = "comment")
public class Comment extends TenantScopedEntity {

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "resource_type", nullable = false)
    private CommentResourceType resourceType;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(nullable = false)
    private String body;

    @Column(name = "edited_at")
    private Instant editedAt;

    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }

    public CommentResourceType getResourceType() { return resourceType; }
    public void setResourceType(CommentResourceType resourceType) { this.resourceType = resourceType; }

    public UUID getResourceId() { return resourceId; }
    public void setResourceId(UUID resourceId) { this.resourceId = resourceId; }

    public UUID getAuthorId() { return authorId; }
    public void setAuthorId(UUID authorId) { this.authorId = authorId; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public Instant getEditedAt() { return editedAt; }
    public void setEditedAt(Instant editedAt) { this.editedAt = editedAt; }
}
