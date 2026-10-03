package co.ara.onboarding.authz;

import co.ara.onboarding.platform.UserType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class AuthContextProvider {

    private final ActorDirectory actors;

    public AuthContextProvider(ActorDirectory actors) { this.actors = actors; }

    /**
     * Throws rather than returning empty: every caller needs an actor, and a null
     * principal silently treated as "no permissions" would be indistinguishable
     * from an authenticated user who happens to be granted nothing.
     */
    public AuthenticatedPrincipal principal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedPrincipal p)) {
            throw new AccessDeniedException("Not authenticated");
        }
        return p;
    }

    /**
     * The lookup is tenant-scoped by RLS, so a principal naming a user in another
     * tenant resolves to nothing and is rejected here rather than silently
     * producing an AuthContext for a stranger.
     */
    public AuthContext current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SystemPrincipal s) {
            return new AuthContext(s.tenantId(), SystemPrincipal.SYSTEM_USER_ID, UserType.SYSTEM,
                    null, Set.of());
        }
        AuthenticatedPrincipal p = principal();
        AuthContext ctx = actors.findActor(p.userId())
                .orElseThrow(() -> new AccessDeniedException("Unknown user"));
        // SYSTEM is only ever minted above, from a SystemPrincipal. A stored user claiming it
        // is never an actor.
        if (ctx.userType() == UserType.SYSTEM) throw new AccessDeniedException("Unknown user");
        return ctx;
    }

    /** True only for the scheduler's SystemPrincipal -- decided by principal type, never by data. */
    public boolean isSystem() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SystemPrincipal;
    }
}
