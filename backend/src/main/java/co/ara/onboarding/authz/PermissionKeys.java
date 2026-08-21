package co.ara.onboarding.authz;

/**
 * The permission key constants used everywhere else in the codebase.
 *
 * Referenced rather than typed as string literals so a renamed or removed
 * permission is a compile error at every use site instead of a grant that
 * silently stops matching.
 *
 * There is deliberately no catch-all key meaning "skip the check" -- see
 * AuthorizationCoverageTest for why. Platform-admin endpoints are secured at the
 * HTTP layer instead.
 */
public final class PermissionKeys {
    public static final String TENANT_SETTINGS_VIEW = "tenant.settings.view";
    public static final String TENANT_SETTINGS_EDIT = "tenant.settings.edit";
    public static final String USER_VIEW            = "user.view";
    public static final String USER_MANAGE          = "user.manage";
    public static final String ROLE_VIEW            = "role.view";
    public static final String ROLE_MANAGE          = "role.manage";
    public static final String DEPARTMENT_MANAGE    = "department.manage";
    public static final String TEAM_MANAGE          = "team.manage";
    public static final String CUSTOMER_VIEW        = "customer.view";
    public static final String CUSTOMER_CREATE      = "customer.create";
    public static final String CUSTOMER_EDIT        = "customer.edit";
    public static final String CUSTOMER_DEACTIVATE  = "customer.deactivate";
    public static final String CONTACT_VIEW         = "contact.view";
    public static final String CONTACT_MANAGE       = "contact.manage";
    public static final String INVITATION_SEND      = "invitation.send";
    public static final String AUDIT_VIEW           = "audit.view";
    public static final String WORKFLOW_VIEW        = "workflow.view";
    public static final String WORKFLOW_MANAGE      = "workflow.manage";

    private PermissionKeys() {}
}
