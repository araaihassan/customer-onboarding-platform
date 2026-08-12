package co.ara.onboarding.tenancy;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PathPrefixTenantResolver implements TenantResolver {

    private static final Pattern TENANT_PATH =
            Pattern.compile("^/api/t/([a-z0-9][a-z0-9-]{0,62})(/.*)?$");

    @Override
    public Optional<String> resolveSlug(HttpServletRequest request) {
        Matcher m = TENANT_PATH.matcher(request.getRequestURI());
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
