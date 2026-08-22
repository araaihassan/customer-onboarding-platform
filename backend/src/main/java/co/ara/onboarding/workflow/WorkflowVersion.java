package co.ara.onboarding.workflow;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * One numbered revision of a {@link WorkflowTemplate}'s definition. Exactly one
 * DRAFT may exist per template at a time (workflow_version_one_draft_per_template);
 * once PUBLISHED it is frozen at the storage layer by the refuse_published_*
 * triggers in V12__workflow.sql, never by service discipline.
 *
 * lockVersion is a genuine JPA optimistic lock, not decoration: two administrators
 * editing one draft is the normal case in a small tenant, and last-writer-wins on a
 * whole-graph PUT would silently discard the other's stages.
 */
@Entity
@Table(name = "workflow_version")
public class WorkflowVersion extends TenantScopedEntity {

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VersionStatus status;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private UUID publishedBy;

    public UUID getTemplateId() { return templateId; }
    public void setTemplateId(UUID templateId) { this.templateId = templateId; }

    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int versionNo) { this.versionNo = versionNo; }

    public VersionStatus getStatus() { return status; }
    public void setStatus(VersionStatus status) { this.status = status; }

    public long getLockVersion() { return lockVersion; }
    public void setLockVersion(long lockVersion) { this.lockVersion = lockVersion; }

    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }

    public UUID getPublishedBy() { return publishedBy; }
    public void setPublishedBy(UUID publishedBy) { this.publishedBy = publishedBy; }
}
