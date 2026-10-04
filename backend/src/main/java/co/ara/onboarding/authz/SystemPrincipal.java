package co.ara.onboarding.authz;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

/**
 * The principal a scheduled job runs as (spec 3.4, 1.2.3). A distinct type, so no JWT --
 * which JwtAuthenticationFilter only ever turns into an AuthenticatedPrincipal -- can produce
 * it. Bound to one tenant: AuthorizationService grants nothing when the thread's tenant differs.
 */
public record SystemPrincipal(UUID tenantId) {

    /** The nil UUID: never a Uuid7, so never an app_user id. Used as AuthContext.userId(). */
    public static final UUID SYSTEM_USER_ID = new UUID(0L, 0L);

    public static Authentication authentication(UUID tenantId) {
        return new UsernamePasswordAuthenticationToken(new SystemPrincipal(tenantId), null, List.of());
    }
}
