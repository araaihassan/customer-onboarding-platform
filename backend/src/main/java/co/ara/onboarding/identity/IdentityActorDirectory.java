package co.ara.onboarding.identity;

import co.ara.onboarding.authz.ActorDirectory;
import co.ara.onboarding.authz.AuthContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * {@code @Transactional(readOnly = true)} added in a Task 26 review round,
     * retiring a whole bug class rather than just the one occurrence of it
     * Task 26 itself found: this is a bare repository read with no
     * transaction of its own, and {@code tenancy.TenantTransactionBinder}'s
     * pointcut binds the tenant GUC only on {@code @Transactional}/{@code
     * @within(Transactional)} join points -- so calling this (via {@code
     * AuthContextProvider.current()}) from a controller, which is never
     * {@code @Transactional} in this codebase, risked running before the
     * tenant context was bound, denying everything downstream with a silent,
     * easily-misread-as-correct 403. {@code PortalDocumentController} hit
     * exactly this during Task 26's own development and was fixed at that one
     * call site by switching to {@code principal()}; this annotation fixes
     * the mechanism itself, so a future caller making the same mistake is
     * safe by construction. Spring's default {@code REQUIRED} propagation
     * means this simply joins an already-open transaction for every existing
     * internal caller, with no behavior change for any of them.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<AuthContext> findActor(UUID userId) {
        return users.findById(userId).map(u -> new AuthContext(
                u.getTenantId(), u.getId(), u.getUserType(),
                u.getDepartmentId(), Set.copyOf(u.getTeamIds())));
    }
}
