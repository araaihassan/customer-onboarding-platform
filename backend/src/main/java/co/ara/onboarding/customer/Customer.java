package co.ara.onboarding.customer;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A customer being onboarded.
 *
 * The three ownership columns are not decoration: ownerUserId backs the ASSIGNED
 * scope, owningDepartmentId backs DEPARTMENT, and owningTeamId backs TEAM. Task
 * 13's predicate builder turns a grant's scope into a query predicate over
 * exactly these fields, so leaving one null makes a record invisible to anyone
 * holding only that scope.
 */
@Entity
@Table(name = "customer")
public class Customer extends TenantScopedEntity {

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CustomerStatus status;

    @Column private String industry;

    /** ISO 3166-1 alpha-2. */
    @Column(length = 2) private String country;

    @Column(name = "external_ref") private String externalRef;

    @Column(name = "owner_user_id") private UUID ownerUserId;

    @Column(name = "owning_department_id") private UUID owningDepartmentId;

    @Column(name = "owning_team_id") private UUID owningTeamId;

    @Column(name = "created_by") private UUID createdBy;

    public String getLegalName() { return legalName; }
    public void setLegalName(String legalName) { this.legalName = legalName; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public CustomerStatus getStatus() { return status; }
    public void setStatus(CustomerStatus status) { this.status = status; }

    public String getIndustry() { return industry; }
    public void setIndustry(String industry) { this.industry = industry; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String externalRef) { this.externalRef = externalRef; }

    public UUID getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(UUID ownerUserId) { this.ownerUserId = ownerUserId; }

    public UUID getOwningDepartmentId() { return owningDepartmentId; }
    public void setOwningDepartmentId(UUID owningDepartmentId) { this.owningDepartmentId = owningDepartmentId; }

    public UUID getOwningTeamId() { return owningTeamId; }
    public void setOwningTeamId(UUID owningTeamId) { this.owningTeamId = owningTeamId; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
