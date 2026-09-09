package co.ara.onboarding.programme;

import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A user's participation in a programme. {@code relationshipType} reuses
 * {@code authz.RelationshipType} -- the same vocabulary cases and milestones
 * already extend -- but {@code status} is programme's own enum
 * ({@link ProgrammeParticipantStatus}), not {@code journey.ParticipantStatus},
 * so a later change to journey's participant lifecycle cannot silently change
 * programme's. {@code programme_participant_uq} allows at most one row per
 * (programme, user) pair.
 */
@Entity
@Table(name = "programme_participant")
public class ProgrammeParticipant extends TenantScopedEntity {

    @Column(name = "programme_id", nullable = false)
    private UUID programmeId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type", nullable = false)
    private RelationshipType relationshipType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProgrammeParticipantStatus status;

    public UUID getProgrammeId() { return programmeId; }
    public void setProgrammeId(UUID programmeId) { this.programmeId = programmeId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public RelationshipType getRelationshipType() { return relationshipType; }
    public void setRelationshipType(RelationshipType relationshipType) { this.relationshipType = relationshipType; }

    public ProgrammeParticipantStatus getStatus() { return status; }
    public void setStatus(ProgrammeParticipantStatus status) { this.status = status; }
}
