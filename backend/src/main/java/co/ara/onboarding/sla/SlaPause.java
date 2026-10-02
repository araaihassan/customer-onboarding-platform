package co.ara.onboarding.sla;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A pause interval on a clock (spec 4.4). */
@Entity
@Table(name = "sla_pause")
public class SlaPause extends TenantScopedEntity {
    @Column(name = "clock_id", nullable = false) private UUID clockId;
    @Enumerated(EnumType.STRING) @Column(name = "reason", nullable = false) private PauseReason reason;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "ended_at") private Instant endedAt;

    public UUID getClockId() { return clockId; }
    public void setClockId(UUID clockId) { this.clockId = clockId; }
    public PauseReason getReason() { return reason; }
    public void setReason(PauseReason reason) { this.reason = reason; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
}
