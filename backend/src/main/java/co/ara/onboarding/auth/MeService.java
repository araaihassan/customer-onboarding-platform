package co.ara.onboarding.auth;

import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizationService;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.platform.UserType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The caller's own profile and effective permissions.
 *
 * A service rather than logic in the controller for a concrete reason, not just
 * convention: app_user is RLS-protected, and a repository call from a
 * non-transactional controller runs with no tenant bound on the connection. RLS
 * would return nothing and the lookup would fail — which is exactly what
 * ModuleBoundaryTest.controllersDoNotUseRepositoriesDirectly exists to prevent.
 *
 * NOT permission-gated. It returns only the caller's own record, and there is no
 * catalogued permission for knowing who you are — inventing one would be a
 * permission every role would have to hold, which is the same as no permission at
 * all. AuthorizationCoverageTest excludes it on that basis.
 */
@Service
public class MeService {

    public record Me(UUID id, String fullName, String email, UserType userType,
                     UUID departmentId, Set<UUID> teamIds,
                     Map<String, List<String>> permissions) {}

    private final AuthContextProvider contextProvider;
    private final AuthorizationService authorization;
    private final AppUserRepository users;

    public MeService(AuthContextProvider contextProvider, AuthorizationService authorization,
                     AppUserRepository users) {
        this.contextProvider = contextProvider;
        this.authorization = authorization;
        this.users = users;
    }

    /**
     * The permission map drives UI affordances only. It is convenience, never
     * security — every endpoint enforces independently (spec 10.3), and Task 22's
     * negative suite covers calling an endpoint the UI would have hidden.
     */
    @Transactional(readOnly = true)
    public Me current() {
        UUID userId = contextProvider.principal().userId();
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new AccessDeniedException("Unknown user"));

        Map<String, List<String>> permissions = new LinkedHashMap<>();
        authorization.effectivePermissions().byPermission().forEach((key, scopes) ->
                permissions.put(key, scopes.stream().map(Enum::name).sorted().toList()));

        return new Me(user.getId(), user.getFullName(), user.getEmail(), user.getUserType(),
                user.getDepartmentId(), Set.copyOf(user.getTeamIds()), permissions);
    }
}
