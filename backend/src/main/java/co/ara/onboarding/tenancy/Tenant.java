package co.ara.onboarding.tenancy;

import co.ara.onboarding.platform.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "tenant")
public class Tenant extends BaseEntity {

    @Column(nullable = false, unique = true) private String slug;
    @Column(nullable = false) private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false) private TenantStatus status;

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public TenantStatus getStatus() { return status; }
    public void setStatus(TenantStatus status) { this.status = status; }
}
