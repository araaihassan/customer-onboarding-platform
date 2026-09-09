package co.ara.onboarding.programme;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Groups a customer's parallel journeys (QA Q20). A programme has NO lifecycle of
 * its own -- no hold, no approval, no engine; it is a container, not a runtime.
 * {@code ownerUserId}/{@code owningDepartmentId}/{@code owningTeamId} are what
 * {@code ProgrammeDescriptor}'s DEPARTMENT and TEAM predicates read -- without them
 * both collapse to {@code cb.disjunction()} and only ALL-scoped holders ever see a
 * programme.
 */
@Entity
@Table(name = "programme")
public class Programme extends TenantScopedEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "owning_department_id")
    private UUID owningDepartmentId;

    @Column(name = "owning_team_id")
    private UUID owningTeamId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProgrammeStatus status;

    @Column(name = "created_by")
    private UUID createdBy;

    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public UUID getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(UUID ownerUserId) { this.ownerUserId = ownerUserId; }

    public UUID getOwningDepartmentId() { return owningDepartmentId; }
    public void setOwningDepartmentId(UUID owningDepartmentId) { this.owningDepartmentId = owningDepartmentId; }

    public UUID getOwningTeamId() { return owningTeamId; }
    public void setOwningTeamId(UUID owningTeamId) { this.owningTeamId = owningTeamId; }

    public ProgrammeStatus getStatus() { return status; }
    public void setStatus(ProgrammeStatus status) { this.status = status; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
