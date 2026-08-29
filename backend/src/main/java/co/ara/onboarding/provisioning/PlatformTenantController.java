package co.ara.onboarding.provisioning;

import co.ara.onboarding.tenancy.PathPrefixTenantResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

    /**
     * {@code slug} is constrained against {@link PathPrefixTenantResolver#SLUG_PATTERN}
     * -- the same literal the resolver itself matches incoming requests against,
     * not a retyped copy of it. A slug that satisfies this constraint but not that
     * resolver would be a silent reintroduction of the bug this validation exists
     * to close: a tenant created successfully but never reachable by any request,
     * because every request resolves no slug and answers 401 with no error at
     * creation time.
     */
    public record ProvisionRequest(
            @NotBlank @Pattern(regexp = PathPrefixTenantResolver.SLUG_PATTERN,
                    message = "must be lowercase alphanumeric with hyphens, 1-63 characters")
            String slug,
            @NotBlank String name,
            @NotBlank @Email String adminEmail,
            @NotBlank String adminFullName) {}

    private final TenantProvisioningService provisioning;

    public PlatformTenantController(TenantProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @PostMapping
    public Map<String, UUID> provision(@Valid @RequestBody ProvisionRequest request) {
        UUID id = provisioning.provision(
                request.slug(), request.name(), request.adminEmail(), request.adminFullName());
        return Map.of("tenantId", id);
    }
}
