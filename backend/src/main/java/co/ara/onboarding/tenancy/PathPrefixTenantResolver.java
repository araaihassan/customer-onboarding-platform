package co.ara.onboarding.tenancy;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PathPrefixTenantResolver implements TenantResolver {

    /** The slug shape alone, with no surrounding anchors -- reused below to build
     * {@link #TENANT_PATH}'s capture group, and by {@link #SLUG_PATTERN}. */
    private static final String SLUG_SHAPE = "[a-z0-9][a-z0-9-]{0,62}";

    /**
     * The single literal both this resolver and
     * {@code PlatformTenantController.ProvisionRequest}'s bean validation build on,
     * so the two cannot drift into disagreement. Before this constant existed, only
     * the resolver enforced this shape; a slug it could never parse (mixed case, an
     * underscore) was still accepted at tenant creation, producing a tenant that
     * was permanently unreachable -- every request resolves no slug and answers
     * 401, with no error at creation time.
     */
    public static final String SLUG_PATTERN = "^" + SLUG_SHAPE + "$";

    private static final Pattern TENANT_PATH =
            Pattern.compile("^/api/t/(" + SLUG_SHAPE + ")(/.*)?$");

    @Override
    public Optional<String> resolveSlug(HttpServletRequest request) {
        Matcher m = TENANT_PATH.matcher(request.getRequestURI());
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
