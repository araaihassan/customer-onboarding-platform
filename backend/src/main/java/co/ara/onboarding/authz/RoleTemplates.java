package co.ara.onboarding.authz;

import java.util.List;
import java.util.Map;

import static co.ara.onboarding.authz.PermissionKeys.*;
import static co.ara.onboarding.authz.Scope.*;
import static java.util.Map.entry;

/**
 * The twelve PRD role templates, seeded into every new tenant.
 *
 * Scopes are deliberate starting points, not arbitrary. Operational roles that
 * work case-by-case default to TEAM or ASSIGNED; reviewing roles that must see
 * the whole book of business (Legal, Finance, Compliance) get ALL; Administrator
 * is the only template granted ROLE_MANAGE, so a tenant cannot escalate its own
 * authority through any other seeded role.
 *
 * Tenants copy these and edit the copies; RoleTemplateValidityTest checks every
 * grant here against PermissionCatalog, so a template can never seed a role the
 * catalog would have rejected at write time.
 */
public final class RoleTemplates {

    public record RoleTemplate(String name, String description, Map<String, Scope> grants) {}

    private static final List<RoleTemplate> TEMPLATES = List.of(
        // WORKFLOW_VIEW, ALL joins every operational template here: anyone working a
        // case needs to read the definition it is frozen on. WORKFLOW_MANAGE stays
        // Administrator-only, below -- the same reasoning ROLE_MANAGE already carries.
        new RoleTemplate("Sales Representative", "Owns prospects and new customers", Map.of(
            CUSTOMER_VIEW, ASSIGNED, CUSTOMER_CREATE, ALL, CUSTOMER_EDIT, ASSIGNED,
            CONTACT_VIEW, ASSIGNED, CONTACT_MANAGE, ASSIGNED, INVITATION_SEND, ASSIGNED,
            WORKFLOW_VIEW, ALL)),

        new RoleTemplate("Account Manager", "Owns ongoing customer relationships", Map.of(
            CUSTOMER_VIEW, TEAM, CUSTOMER_EDIT, TEAM, CONTACT_VIEW, TEAM,
            CONTACT_MANAGE, TEAM, INVITATION_SEND, TEAM, USER_VIEW, TEAM,
            WORKFLOW_VIEW, ALL)),

        new RoleTemplate("Project Manager", "Coordinates onboarding delivery", Map.of(
            CUSTOMER_VIEW, TEAM, CUSTOMER_EDIT, TEAM, CONTACT_VIEW, TEAM,
            INVITATION_SEND, TEAM, USER_VIEW, TEAM, AUDIT_VIEW, TEAM,
            WORKFLOW_VIEW, ALL)),

        new RoleTemplate("Service Provider", "Delivers technical services", Map.of(
            CUSTOMER_VIEW, ASSIGNED, CONTACT_VIEW, ASSIGNED, WORKFLOW_VIEW, ALL)),

        new RoleTemplate("Business Partner", "External delivery partner", Map.of(
            CUSTOMER_VIEW, ASSIGNED, CONTACT_VIEW, ASSIGNED, WORKFLOW_VIEW, ALL)),

        new RoleTemplate("Operations", "Runs day-to-day onboarding operations", Map.of(
            CUSTOMER_VIEW, DEPARTMENT, CUSTOMER_EDIT, DEPARTMENT,
            CONTACT_VIEW, DEPARTMENT, USER_VIEW, DEPARTMENT, WORKFLOW_VIEW, ALL)),

        new RoleTemplate("Legal", "Reviews agreements and legal requirements", Map.of(
            CUSTOMER_VIEW, ALL, CONTACT_VIEW, ALL, AUDIT_VIEW, ALL, WORKFLOW_VIEW, ALL)),

        new RoleTemplate("Finance", "Handles billing and financial verification", Map.of(
            CUSTOMER_VIEW, ALL, CONTACT_VIEW, ALL, WORKFLOW_VIEW, ALL)),

        new RoleTemplate("Technical", "Performs technical setup and testing", Map.of(
            CUSTOMER_VIEW, TEAM, CONTACT_VIEW, TEAM, WORKFLOW_VIEW, ALL)),

        new RoleTemplate("Compliance", "Verifies KYC and regulatory requirements", Map.of(
            CUSTOMER_VIEW, ALL, CONTACT_VIEW, ALL, AUDIT_VIEW, ALL, WORKFLOW_VIEW, ALL)),

        new RoleTemplate("Support", "Assists customers post-activation", Map.of(
            CUSTOMER_VIEW, TEAM, CONTACT_VIEW, TEAM, WORKFLOW_VIEW, ALL)),

        // Map.ofEntries, not Map.of: this covers all 18 catalog permissions, and
        // Map.of has no overload beyond 10 key-value pairs.
        new RoleTemplate("Administrator", "Full tenant administration", Map.ofEntries(
            entry(TENANT_SETTINGS_VIEW, ALL), entry(TENANT_SETTINGS_EDIT, ALL),
            entry(USER_VIEW, ALL), entry(USER_MANAGE, ALL),
            entry(ROLE_VIEW, ALL), entry(ROLE_MANAGE, ALL),
            entry(DEPARTMENT_MANAGE, ALL), entry(TEAM_MANAGE, ALL),
            entry(CUSTOMER_VIEW, ALL), entry(CUSTOMER_CREATE, ALL),
            entry(CUSTOMER_EDIT, ALL), entry(CUSTOMER_DEACTIVATE, ALL),
            entry(CONTACT_VIEW, ALL), entry(CONTACT_MANAGE, ALL),
            entry(INVITATION_SEND, ALL), entry(AUDIT_VIEW, ALL),
            entry(WORKFLOW_VIEW, ALL), entry(WORKFLOW_MANAGE, ALL)))
    );

    private RoleTemplates() {}

    public static List<RoleTemplate> all() { return TEMPLATES; }
}
