package co.ara.onboarding.authz;

import java.util.Map;

import static co.ara.onboarding.authz.PermissionKeys.*;

/**
 * The system actor's permission set (invariant 7): a code constant, never a user_role row --
 * the PortalPermissions shape. Read-only on purpose: every write the sweep makes goes to sla's
 * own tables through its own gated service methods, which require sla.view.
 */
public final class SystemPermissions {
    private SystemPermissions() {}

    public static Map<String, Scope> forJobs() {
        return Map.of(CASE_VIEW, Scope.ALL, TASK_VIEW, Scope.ALL, SLA_VIEW, Scope.ALL);
    }
}
