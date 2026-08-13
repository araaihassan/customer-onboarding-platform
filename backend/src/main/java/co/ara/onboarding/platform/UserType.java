package co.ara.onboarding.platform;

/**
 * Whether a user is staff of the tenant or an external contact using the portal.
 *
 * IN PLATFORM, NOT IDENTITY, AND DELIBERATELY SO. identity owns user storage and
 * authz owns policy, and both need this classification: identity persists it on
 * app_user, and AuthContext carries it so authorization can treat a portal actor
 * differently from an internal one. Putting it in identity would force
 * authz -> identity, which cycles against identity -> authz as soon as identity
 * gains a permission-gated service (Task 21's UserAdminService).
 *
 * platform is the one module both may depend on. RequestAuditContext.ActorType is
 * the same pattern: a cross-cutting classification kept in the foundation.
 */
public enum UserType { INTERNAL, PORTAL }
