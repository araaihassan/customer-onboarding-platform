package co.ara.onboarding.workflow;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A checkpoint inside a {@link Stage}. estimatedDurationDays is Q6's weight and the
 * schedule's unit -- NOT NULL, because a milestone with no duration contributes
 * nothing to a weighted progress calculation, which reads as a milestone that does
 * not count.
 */
@Entity
@Table(name = "milestone_definition")
public class MilestoneDefinition extends TenantScopedEntity {

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "stage_id", nullable = false)
    private UUID stageId;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column(name = "estimated_duration_days", nullable = false)
    private int estimatedDurationDays;

    public UUID getVersionId() { return versionId; }
    public void setVersionId(UUID versionId) { this.versionId = versionId; }

    public UUID getStageId() { return stageId; }
    public void setStageId(UUID stageId) { this.stageId = stageId; }

    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getEstimatedDurationDays() { return estimatedDurationDays; }
    public void setEstimatedDurationDays(int estimatedDurationDays) { this.estimatedDurationDays = estimatedDurationDays; }
}
