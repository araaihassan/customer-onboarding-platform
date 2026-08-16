package co.ara.onboarding.authz;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "role")
public class Role extends TenantScopedEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description = "";

    /** Seeded from RoleTemplates (Task 10); tenants may copy but not edit these. */
    @Column(name = "system_template", nullable = false)
    private boolean systemTemplate = false;

    @Column(nullable = false)
    private boolean enabled = true;

    /**
     * EAGER because a role is meaningless without its grants: every caller that
     * loads a Role does so to read them, and resolving effective permissions
     * (Task 13) walks a user's roles on every request.
     *
     * orphanRemoval with cascade ALL is what lets updateGrants replace the set
     * wholesale by clearing and re-adding.
     */
    @OneToMany(mappedBy = "role", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Set<RoleGrant> grants = new HashSet<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isSystemTemplate() { return systemTemplate; }
    public void setSystemTemplate(boolean systemTemplate) { this.systemTemplate = systemTemplate; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Set<RoleGrant> getGrants() { return grants; }
    public void setGrants(Set<RoleGrant> grants) { this.grants = grants; }
}
