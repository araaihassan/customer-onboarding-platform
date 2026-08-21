package co.ara.onboarding.journey;

import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A user's relationship to a {@link Case}. ASSIGNED scope resolves through these
 * rows -- the first real use of ResourceAuthorizationDescriptor's
 * assignedRelationships(). A participant who leaves transitions to REMOVED; the row
 * is never deleted, because a participation that vanished leaves an unexplained gap
 * in the case's history.
 */
@Entity
@Table(name = "case_participant")
public class CaseParticipant extends TenantScopedEntity {

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RelationshipType relationship;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ParticipantStatus status;

    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public RelationshipType getRelationship() { return relationship; }
    public void setRelationship(RelationshipType relationship) { this.relationship = relationship; }

    public ParticipantStatus getStatus() { return status; }
    public void setStatus(ParticipantStatus status) { this.status = status; }
}
