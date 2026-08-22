package co.ara.onboarding.workflow;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A conditional next-stage transition out of a {@link Stage}. Unlike Stage's entry
 * condition, this embeds {@link Condition} onto the bare column names -- the
 * condition is the rule's whole reason to exist, not one optional facet of it.
 */
@Entity
@Table(name = "branch_rule")
public class BranchRule extends TenantScopedEntity {

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "stage_id", nullable = false)
    private UUID stageId;

    @Column(nullable = false)
    private int ordinal;

    @Embedded
    private Condition condition = new Condition();

    @Column(name = "target_stage_id", nullable = false)
    private UUID targetStageId;

    public UUID getVersionId() { return versionId; }
    public void setVersionId(UUID versionId) { this.versionId = versionId; }

    public UUID getStageId() { return stageId; }
    public void setStageId(UUID stageId) { this.stageId = stageId; }

    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }

    public Condition getCondition() { return condition; }
    public void setCondition(Condition condition) { this.condition = condition; }

    public UUID getTargetStageId() { return targetStageId; }
    public void setTargetStageId(UUID targetStageId) { this.targetStageId = targetStageId; }
}
