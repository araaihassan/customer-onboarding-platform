package co.ara.onboarding.audit;

import java.util.*;

/**
 * The finite registry of auditable actions. Every call to
 * {@link AuditRecorder#record} must use one of these constants; there is no
 * way to record an event whose action key was not seeded here. timelineVisible
 * marks which actions surface on the user-facing Activity Timeline
 * (sub-project 2) versus compliance-only events that stay in the audit log.
 */
public final class AuditActions {

    // Declared before the constants below: static fields initialize in
    // textual order, and of() writes into this map, so if BY_KEY were
    // declared after the constants it would still be null when the first
    // of("tenant.created", ...) call ran, throwing NullPointerException out
    // of the class's static initializer (ExceptionInInitializerError).
    // Verified empirically -- this is what happens with the field below the
    // constants.
    private static final Map<String, AuditAction> BY_KEY = new LinkedHashMap<>();

    public static final AuditAction TENANT_CREATED            = of("tenant.created", false);
    public static final AuditAction USER_CREATED              = of("user.created", false);
    public static final AuditAction USER_ROLE_ASSIGNED        = of("user.role_assigned", false);
    public static final AuditAction ROLE_CREATED              = of("role.created", false);
    public static final AuditAction ROLE_UPDATED              = of("role.updated", false);
    public static final AuditAction ROLE_DISABLED             = of("role.disabled", false);
    public static final AuditAction LOGIN_SUCCEEDED           = of("auth.login_succeeded", false);
    public static final AuditAction LOGIN_FAILED              = of("auth.login_failed", false);
    public static final AuditAction REFRESH_REUSE_DETECTED    = of("auth.refresh_reuse_detected", false);
    public static final AuditAction CUSTOMER_CREATED          = of("customer.created", true);
    public static final AuditAction CUSTOMER_UPDATED          = of("customer.updated", true);
    public static final AuditAction CUSTOMER_DEACTIVATED      = of("customer.deactivated", true);
    public static final AuditAction INVITATION_SENT           = of("invitation.sent", true);
    public static final AuditAction INVITATION_ACCEPTED       = of("invitation.accepted", true);

    private static AuditAction of(String key, boolean timelineVisible) {
        AuditAction a = new AuditAction(key, timelineVisible);
        BY_KEY.put(key, a);
        return a;
    }

    private AuditActions() {}

    public static Collection<AuditAction> all() { return List.copyOf(BY_KEY.values()); }

    public static Optional<AuditAction> byKey(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }
}
