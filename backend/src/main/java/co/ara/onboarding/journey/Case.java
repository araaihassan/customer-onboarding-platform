package co.ara.onboarding.journey;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A customer's run through one pinned {@code workflow_version}. "Case" is a legal
 * Java class name; the table is named {@code onboarding_case} because {@code case}
 * is a SQL reserved word.
 *
 * versionId is NOT NULL at the database layer: a case with no pinned version has no
 * definition to execute, and Q2's freeze-by-default is that column plus V12's
 * frozen-workflow triggers, nothing else.
 */
@Entity
@Table(name = "onboarding_case")
public class Case extends TenantScopedEntity {

    /**
     * Q18: a human-readable name set at creation -- "Enterprise onboarding", "EU
     * expansion". {@code length = 160} matches V15's {@code varchar(160)} --
     * Flyway, not Hibernate, owns the actual DDL here, so this is documentation
     * rather than enforcement; {@code @Size(max = 160)} on CreateCaseRequest/
     * UpdateCaseRequest is what actually stops an over-length name before it
     * reaches this column (fix round 2).
     */
    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaseStatus status;

    @Column(name = "current_stage_id")
    private UUID currentStageId;

    /** Written only by CaseEngine, never from a request body. */
    @Column(name = "progress_percent", nullable = false)
    private int progressPercent;

    @Column(name = "target_completion_date")
    private LocalDate targetCompletionDate;

    @Column(name = "held_at")
    private Instant heldAt;

    @Column(name = "total_hold_days", nullable = false)
    private int totalHoldDays;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "owning_department_id")
    private UUID owningDepartmentId;

    @Column(name = "owning_team_id")
    private UUID owningTeamId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    /**
     * Set by CaseService.advance immediately before it calls CaseEngine.reconcile,
     * on the specific in-memory instance that call locked -- never persisted, and
     * never read back on a later request. A persisted flag would let a manual stage
     * advance itself on the very next unrelated reconcile (e.g. a satisfy call),
     * which defeats the point of auto_advance=false.
     */
    @Transient
    private boolean advanceRequested;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }

    public UUID getTemplateId() { return templateId; }
    public void setTemplateId(UUID templateId) { this.templateId = templateId; }

    public UUID getVersionId() { return versionId; }
    public void setVersionId(UUID versionId) { this.versionId = versionId; }

    public CaseStatus getStatus() { return status; }
    public void setStatus(CaseStatus status) { this.status = status; }

    public UUID getCurrentStageId() { return currentStageId; }
    public void setCurrentStageId(UUID currentStageId) { this.currentStageId = currentStageId; }

    public int getProgressPercent() { return progressPercent; }
    public void setProgressPercent(int progressPercent) { this.progressPercent = progressPercent; }

    public LocalDate getTargetCompletionDate() { return targetCompletionDate; }
    public void setTargetCompletionDate(LocalDate targetCompletionDate) { this.targetCompletionDate = targetCompletionDate; }

    public Instant getHeldAt() { return heldAt; }
    public void setHeldAt(Instant heldAt) { this.heldAt = heldAt; }

    public int getTotalHoldDays() { return totalHoldDays; }
    public void setTotalHoldDays(int totalHoldDays) { this.totalHoldDays = totalHoldDays; }

    public UUID getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(UUID ownerUserId) { this.ownerUserId = ownerUserId; }

    public UUID getOwningDepartmentId() { return owningDepartmentId; }
    public void setOwningDepartmentId(UUID owningDepartmentId) { this.owningDepartmentId = owningDepartmentId; }

    public UUID getOwningTeamId() { return owningTeamId; }
    public void setOwningTeamId(UUID owningTeamId) { this.owningTeamId = owningTeamId; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public boolean isAdvanceRequested() { return advanceRequested; }
    public void setAdvanceRequested(boolean advanceRequested) { this.advanceRequested = advanceRequested; }
}
