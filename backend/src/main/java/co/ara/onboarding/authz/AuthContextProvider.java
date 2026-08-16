package co.ara.onboarding.authz;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

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
        AuthenticatedPrincipal p = principal();
        return actors.findActor(p.userId())
                .orElseThrow(() -> new AccessDeniedException("Unknown user"));
    }
}
