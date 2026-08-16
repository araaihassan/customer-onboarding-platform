package co.ara.onboarding.tenancy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

public interface TenantResolver {
    Optional<String> resolveSlug(HttpServletRequest request);
}
