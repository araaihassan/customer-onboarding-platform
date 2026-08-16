package co.ara.onboarding.authz;

import java.util.Optional;
import java.util.UUID;

/**
 * Supplies the identity facts authorization needs about an actor.
 *
 * This is a port, and it exists to keep the dependency pointing one way. authz
 * needs a user's department and teams to resolve DEPARTMENT and TEAM scope, but it
 * must not depend on identity: identity depends on authz already (Task 21's
 * permission-gated UserAdminService), so an authz -> identity import would close a
 * cycle that ModuleBoundaryTest rejects.
 *
 * So authz declares what it needs and identity provides it —
 * identity.IdentityActorDirectory is the implementation. The alternative, querying
 * app_user and team_member with raw SQL from inside authz, would duplicate schema
 * knowledge that identity owns.
 */
public interface ActorDirectory {

    /** Empty when no such user exists in the bound tenant. */
    Optional<AuthContext> findActor(UUID userId);
}
