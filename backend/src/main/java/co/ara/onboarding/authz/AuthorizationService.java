package co.ara.onboarding.authz;

import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;


/**
 * Request-scoped: effective permissions are resolved once per request and never
 * cached across requests, so a role change takes effect on the next request with
 * no stale authority (spec 6.7).
 *
 * The memo field is safe precisely because the bean is request-scoped — the same
 * field on a singleton would hand one user's authority to the next.
 */
@Component
@RequestScope
public class AuthorizationService {

    private final GrantLookup grants;
    private final AuthContextProvider contextProvider;
    private final PortalContactDirectory contacts;
    private EffectivePermissions memo;

    /**
     * Still takes the JdbcTemplate (security.SystemActorTest constructs this class by hand
     * from outside the package, and GrantLookup is package-private): the grant query is the
     * same GrantLookup code RecipientAccess runs, so the two cannot drift.
     */
    public AuthorizationService(JdbcTemplate jdbc, AuthContextProvider contextProvider,
                                PortalContactDirectory contacts) {
        this.grants = new GrantLookup(jdbc);
        this.contextProvider = contextProvider;
        this.contacts = contacts;
    }

    public EffectivePermissions effectivePermissions() {
        if (memo != null) return memo;

        AuthContext actor = contextProvider.current();

        // The scheduler's actor: a code constant, never a role row (invariant 7), decided by the
        // Authentication's principal type and not by any stored user_type, and only while the
        // thread is bound to the tenant the principal was minted for.
        if (contextProvider.isSystem()) {
            memo = actor.tenantId().equals(TenantContext.getOrNull())
                    ? EffectivePermissions.of(SystemPermissions.forJobs())
                    : EffectivePermissions.none();
            return memo;
        }

        // A PORTAL actor holds no roles by construction (RoleService.assignRole
        // refuses one outright), so the role join below would simply return no
        // rows and the actor would read nothing. Their authority is derived from
        // the contact record instead, in code -- see PortalPermissions for why
        // this is deliberately not role-shaped.
        //
        // Both status checks PortalContactDirectory performs matter: the
        // app_user check mirrors the u.status = 'ACTIVE' join in GrantLookup, and the
        // contact check is what makes retirement take effect on the very next
        // request, not when an access token eventually expires.
        if (actor.userType() == UserType.PORTAL) {
            memo = contacts.findActiveContactForUser(actor.userId())
                    .map(c -> EffectivePermissions.of(
                            c.isSponsor() ? PortalPermissions.forSponsor() : PortalPermissions.forContact()))
                    .orElseGet(EffectivePermissions::none);
            return memo;
        }

        // The grant query itself lives in GrantLookup, shared with RecipientAccess so the
        // current-actor path and the another-user path can never disagree about what a grant
        // means -- including the u.status = 'ACTIVE' join that makes a deactivated user hold
        // nothing on the very next request. See GrantLookup for the full reasoning.
        memo = grants.forInternalUser(actor.userId());
        return memo;
    }

    public boolean has(String permissionKey) {
        return effectivePermissions().has(permissionKey);
    }
}
