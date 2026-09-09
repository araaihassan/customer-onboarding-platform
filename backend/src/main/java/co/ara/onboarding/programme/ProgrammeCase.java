package co.ara.onboarding.programme;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A case's membership in a programme. No status enum of its own -- membership is
 * expressed by {@code removedAt} alone: null while active, set when the case
 * leaves. {@code programme_case_active_uq} is a PARTIAL unique index on
 * {@code case_id} (only where {@code removed_at IS NULL}), not a plain
 * {@code UNIQUE(case_id)} -- a case belongs to at most one programme AT A TIME,
 * but DELETE is revoked at the database layer, so leaving one programme and
 * joining another must stay possible: the row that recorded the old membership is
 * closed (removed_at set), never removed.
 */
@Entity
@Table(name = "programme_case")
public class ProgrammeCase extends TenantScopedEntity {

    @Column(name = "programme_id", nullable = false)
    private UUID programmeId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @Column(name = "added_by")
    private UUID addedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    public UUID getProgrammeId() { return programmeId; }
    public void setProgrammeId(UUID programmeId) { this.programmeId = programmeId; }

    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }

    public Instant getAddedAt() { return addedAt; }
    public void setAddedAt(Instant addedAt) { this.addedAt = addedAt; }

    public UUID getAddedBy() { return addedBy; }
    public void setAddedBy(UUID addedBy) { this.addedBy = addedBy; }

    public Instant getRemovedAt() { return removedAt; }
    public void setRemovedAt(Instant removedAt) { this.removedAt = removedAt; }
}
