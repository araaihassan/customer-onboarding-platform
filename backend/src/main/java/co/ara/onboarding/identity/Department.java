package co.ara.onboarding.identity;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "department")
public class Department extends TenantScopedEntity {

    @Column(nullable = false) private String name;
    @Column private String description;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
