package co.ara.onboarding.identity;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "department")
public class Department extends TenantScopedEntity {

    @Column(nullable = false) private String name;
    @Column private String description;

    @Column(name = "head_user_id") private UUID headUserId;
    public UUID getHeadUserId() { return headUserId; }
    public void setHeadUserId(UUID headUserId) { this.headUserId = headUserId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
