package co.ara.onboarding.provisioning;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Sits in provisioning alongside the service rather than in tenancy. Leaving the
 * controller behind in tenancy would reintroduce the cycle the service was moved
 * to avoid: tenancy -> provisioning -> tenancy.
 *
 * Unauthenticated in this task. Task 22 Step 9 is what restricts /api/platform/**
 * to platform administrators, and it is the only thing standing between this
 * endpoint and anyone creating tenants -- there is deliberately no permission
 * check inside the service.
 */
@RestController
@RequestMapping("/api/platform/tenants")
public class PlatformTenantController {

    public record ProvisionRequest(String slug, String name, String adminEmail, String adminFullName) {}

    private final TenantProvisioningService provisioning;

    public PlatformTenantController(TenantProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @PostMapping
    public Map<String, UUID> provision(@RequestBody ProvisionRequest request) {
        UUID id = provisioning.provision(
                request.slug(), request.name(), request.adminEmail(), request.adminFullName());
        return Map.of("tenantId", id);
    }
}
