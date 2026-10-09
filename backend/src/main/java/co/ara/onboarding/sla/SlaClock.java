package co.ara.onboarding.sla;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One stage visit's SLA clock (spec 4.4, 5). Elapsed time is never stored. */
@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "sla_clock")
public class SlaClock extends TenantScopedEntity {
    @Column(name = "case_id", nullable = false) private UUID caseId;
    @Column(name = "stage_id", nullable = false) private UUID stageId;
    @Column(name = "target_days", nullable = false) private int targetDays;
    @Column(name = "pause_eligible", nullable = false) private boolean pauseEligible;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "stopped_at") private Instant stoppedAt;
    @Enumerated(EnumType.STRING) @Column(name = "outcome") private SlaClockOutcome outcome;
    @Column(name = "breached_at") private Instant breachedAt;
    @Column(name = "at_risk_alerted_at") private Instant atRiskAlertedAt;

    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }
    public UUID getStageId() { return stageId; }
    public void setStageId(UUID stageId) { this.stageId = stageId; }
    public int getTargetDays() { return targetDays; }
    public void setTargetDays(int targetDays) { this.targetDays = targetDays; }
    public boolean isPauseEligible() { return pauseEligible; }
    public void setPauseEligible(boolean pauseEligible) { this.pauseEligible = pauseEligible; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getStoppedAt() { return stoppedAt; }
    public void setStoppedAt(Instant stoppedAt) { this.stoppedAt = stoppedAt; }
    public SlaClockOutcome getOutcome() { return outcome; }
    public void setOutcome(SlaClockOutcome outcome) { this.outcome = outcome; }
    public Instant getBreachedAt() { return breachedAt; }
    public void setBreachedAt(Instant breachedAt) { this.breachedAt = breachedAt; }
    public Instant getAtRiskAlertedAt() { return atRiskAlertedAt; }
    public void setAtRiskAlertedAt(Instant atRiskAlertedAt) { this.atRiskAlertedAt = atRiskAlertedAt; }
}
