package co.ara.onboarding.journey;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The runtime instance of a {@code requirement_definition} within one
 * {@link Milestone}. satisfiedRef/satisfiedRefType are deliberately NOT a foreign
 * key: the target is a task, document or agreement in a module that does not exist
 * yet, and an FK would reproduce sub-project 1's cross-tenant existence oracle --
 * PostgreSQL checks referential integrity with row security bypassed, so another
 * tenant's id answers 200 while an invented one answers 500. A soft reference
 * resolved through AuthorizedQuery cannot.
 */
@Entity
@Table(name = "requirement")
public class Requirement extends TenantScopedEntity {

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "milestone_id", nullable = false)
    private UUID milestoneId;

    @Column(name = "requirement_definition_id", nullable = false)
    private UUID requirementDefinitionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequirementStatus status;

    @Column(name = "satisfied_at")
    private Instant satisfiedAt;

    @Column(name = "satisfied_by")
    private UUID satisfiedBy;

    @Column(name = "satisfied_ref")
    private UUID satisfiedRef;

    @Column(name = "satisfied_ref_type")
    private String satisfiedRefType;

    @Column(name = "waiver_reason")
    private String waiverReason;

    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }

    public UUID getMilestoneId() { return milestoneId; }
    public void setMilestoneId(UUID milestoneId) { this.milestoneId = milestoneId; }

    public UUID getRequirementDefinitionId() { return requirementDefinitionId; }
    public void setRequirementDefinitionId(UUID requirementDefinitionId) { this.requirementDefinitionId = requirementDefinitionId; }

    public RequirementStatus getStatus() { return status; }
    public void setStatus(RequirementStatus status) { this.status = status; }

    public Instant getSatisfiedAt() { return satisfiedAt; }
    public void setSatisfiedAt(Instant satisfiedAt) { this.satisfiedAt = satisfiedAt; }

    public UUID getSatisfiedBy() { return satisfiedBy; }
    public void setSatisfiedBy(UUID satisfiedBy) { this.satisfiedBy = satisfiedBy; }

    public UUID getSatisfiedRef() { return satisfiedRef; }
    public void setSatisfiedRef(UUID satisfiedRef) { this.satisfiedRef = satisfiedRef; }

    public String getSatisfiedRefType() { return satisfiedRefType; }
    public void setSatisfiedRefType(String satisfiedRefType) { this.satisfiedRefType = satisfiedRefType; }

    public String getWaiverReason() { return waiverReason; }
    public void setWaiverReason(String waiverReason) { this.waiverReason = waiverReason; }
}
