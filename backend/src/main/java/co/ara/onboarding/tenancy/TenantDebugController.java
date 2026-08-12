package co.ara.onboarding.tenancy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

// TODO(task-20): remove
@RestController
@RequestMapping("/api/t/{tenantSlug}/_debug")
public class TenantDebugController {

    private final TenantRepository tenants;
    private final JdbcTemplate jdbc;

    public TenantDebugController(TenantRepository tenants, JdbcTemplate jdbc) {
        this.tenants = tenants;
        this.jdbc = jdbc;
    }

    @GetMapping("/tenant")
    public String currentTenant() {
        return tenants.findById(TenantContext.getRequired()).orElseThrow().getSlug();
    }

    @GetMapping("/tenant-setting")
    @Transactional(readOnly = true)
    public String currentTenantSetting() {
        return jdbc.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class);
    }
}
