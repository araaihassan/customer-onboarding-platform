package co.ara.onboarding.identity;

import co.ara.onboarding.authz.ActorDirectory;
import co.ara.onboarding.authz.AuthContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * identity's side of the authz ActorDirectory port. The dependency runs
 * identity -> authz on purpose; see ActorDirectory for why the reverse would
 * close a cycle.
 */
@Component
public class IdentityActorDirectory implements ActorDirectory {

    private final AppUserRepository users;

    public IdentityActorDirectory(AppUserRepository users) { this.users = users; }

    @Override
    public Optional<AuthContext> findActor(UUID userId) {
        return users.findById(userId).map(u -> new AuthContext(
                u.getTenantId(), u.getId(), u.getUserType(),
                u.getDepartmentId(), Set.copyOf(u.getTeamIds())));
    }
}
