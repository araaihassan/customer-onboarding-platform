package co.ara.onboarding.workflow;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A named workflow, e.g. "Standard Onboarding". A business record -- deactivated
 * (status = INACTIVE), never deleted -- unlike the seven definition tables that
 * hang off its versions, which carry an explicit DELETE grant. See V12__workflow.sql.
 */
@Entity
@Table(name = "workflow_template")
public class WorkflowTemplate extends TenantScopedEntity {

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TemplateStatus status;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Column(name = "created_by")
    private UUID createdBy;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public TemplateStatus getStatus() { return status; }
    public void setStatus(TemplateStatus status) { this.status = status; }

    public UUID getCurrentVersionId() { return currentVersionId; }
    public void setCurrentVersionId(UUID currentVersionId) { this.currentVersionId = currentVersionId; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
