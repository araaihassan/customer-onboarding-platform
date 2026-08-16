package co.ara.onboarding.identity;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "team")
public class Team extends TenantScopedEntity {

    @Column(nullable = false) private String name;
    @Column private String description;
    @Column(name = "department_id") private UUID departmentId;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public UUID getDepartmentId() { return departmentId; }
    public void setDepartmentId(UUID departmentId) { this.departmentId = departmentId; }
}
