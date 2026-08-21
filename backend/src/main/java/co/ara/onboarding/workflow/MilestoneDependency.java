package co.ara.onboarding.workflow;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/** An ordering constraint between two milestones in the same version. */
@Entity
@Table(name = "milestone_dependency")
public class MilestoneDependency extends TenantScopedEntity {

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "milestone_definition_id", nullable = false)
    private UUID milestoneDefinitionId;

    @Column(name = "depends_on_milestone_definition_id", nullable = false)
    private UUID dependsOnMilestoneDefinitionId;

    public UUID getVersionId() { return versionId; }
    public void setVersionId(UUID versionId) { this.versionId = versionId; }

    public UUID getMilestoneDefinitionId() { return milestoneDefinitionId; }
    public void setMilestoneDefinitionId(UUID milestoneDefinitionId) { this.milestoneDefinitionId = milestoneDefinitionId; }

    public UUID getDependsOnMilestoneDefinitionId() { return dependsOnMilestoneDefinitionId; }
    public void setDependsOnMilestoneDefinitionId(UUID dependsOnMilestoneDefinitionId) { this.dependsOnMilestoneDefinitionId = dependsOnMilestoneDefinitionId; }
}
