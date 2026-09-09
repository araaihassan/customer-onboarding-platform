package co.ara.onboarding.authz;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static co.ara.onboarding.authz.PermissionKeys.*;
import static co.ara.onboarding.authz.Scope.*;

/**
 * The finite, code-defined permission catalog. Tenants configure roles; they
 * never invent permissions (spec 6.2).
 *
 * This class is the authority. PermissionSyncRunner mirrors it into the
 * permission table at startup, but nothing reads that table to decide authority
 * -- so a row edited by hand in the database grants nothing.
 *
 * DECLARATION ORDER IS LOAD-BEARING. BY_KEY and the three scope sets are
 * declared before the static initializer that uses them. Static initializers run
 * in declaration order, so moving any of them below the static block leaves it
 * null at the moment add() first dereferences it, and the class fails to load
 * with ExceptionInInitializerError. AuditActions (Task 6) shipped with exactly
 * that bug.
 */
public final class PermissionCatalog {

    private static final Map<String, Permission> BY_KEY = new LinkedHashMap<>();

    private static final Set<Scope> ALL_ONLY   = EnumSet.of(ALL);
    private static final Set<Scope> ORG_SCOPES = EnumSet.of(ALL, DEPARTMENT, TEAM);
    private static final Set<Scope> RECORD     = EnumSet.allOf(Scope.class);

    static {
        //  key                  category     resourceType        description                       scopes
        add(TENANT_SETTINGS_VIEW, "tenant",   null,               "View tenant settings",           ALL_ONLY);
        add(TENANT_SETTINGS_EDIT, "tenant",   null,               "Edit tenant settings",           ALL_ONLY);
        add(USER_VIEW,            "identity", "app_user",         "View users",                     ORG_SCOPES);
        add(USER_MANAGE,          "identity", "app_user",         "Create and modify users",        ORG_SCOPES);
        add(ROLE_VIEW,            "authz",    null,               "View roles and grants",          ALL_ONLY);
        add(ROLE_MANAGE,          "authz",    null,               "Create and modify roles",        ALL_ONLY);
        add(DEPARTMENT_MANAGE,    "identity", null,               "Manage departments",             ALL_ONLY);
        add(TEAM_MANAGE,          "identity", null,               "Manage teams",                   ALL_ONLY);
        add(CUSTOMER_VIEW,        "customer", "customer",         "View customers",                 RECORD);
        add(CUSTOMER_CREATE,      "customer", null,               "Create customers",               ALL_ONLY);
        add(CUSTOMER_EDIT,        "customer", "customer",         "Edit customers",                 RECORD);
        add(CUSTOMER_DEACTIVATE,  "customer", "customer",         "Deactivate customers",           ORG_SCOPES);
        add(CONTACT_VIEW,         "customer", "customer_contact", "View customer contacts",         RECORD);
        add(CONTACT_MANAGE,       "customer", "customer_contact", "Manage customer contacts",       RECORD);
        add(INVITATION_SEND,      "customer", "customer_contact", "Send portal invitations",        RECORD);
        add(AUDIT_VIEW,           "audit",    "audit_event",      "View the audit log",             ORG_SCOPES);
        // ALL-only with a null resourceType: a workflow definition is tenant-wide
        // configuration with no owner, so there is nothing for DEPARTMENT or TEAM to
        // resolve against. No descriptor is needed and none can break
        // DescriptorRegistry.validate() -- that is why these two land ahead of Task
        // 11's thirteen journey keys, which do need descriptors.
        add(WORKFLOW_VIEW,        "workflow", null,               "View workflow definitions",      ALL_ONLY);
        add(WORKFLOW_MANAGE,      "workflow", null,               "Create and edit workflows",      ALL_ONLY);
        add(CASE_VIEW,               "journey", "onboarding_case", "View cases",                    RECORD);
        add(CASE_CREATE,             "journey", null,              "Open a case on a customer",     ALL_ONLY);
        add(CASE_EDIT,               "journey", "onboarding_case", "Edit case owner, participants and attributes", RECORD);
        add(CASE_ADVANCE,            "journey", "onboarding_case", "Advance a case to the next stage", RECORD);
        add(CASE_HOLD,               "journey", "onboarding_case", "Place a case on hold or resume it", RECORD);
        add(CASE_MIGRATE,            "journey", null,              "Migrate cases to a new workflow version", ALL_ONLY);
        add(MILESTONE_EDIT,          "journey", "milestone",       "Reassign or reschedule a milestone", RECORD);
        add(MILESTONE_COMPLETE,      "journey", "milestone",       "Satisfy requirements and complete milestones", RECORD);
        add(MILESTONE_REOPEN,        "journey", "milestone",       "Reopen a completed milestone",  ORG_SCOPES);
        add(MILESTONE_FORCE_COMPLETE,"journey", "milestone",       "Request a forced completion",   ORG_SCOPES);
        add(MILESTONE_FORCE_APPROVE, "journey", null,              "Approve a forced completion",   ALL_ONLY);
        add(REQUIREMENT_WAIVE,       "journey", "requirement",     "Waive a requirement",           ORG_SCOPES);
        add(APPROVAL_DECIDE,         "journey", "approval",        "Decide a stage-exit approval",  ORG_SCOPES);
        // Task 12 (sub-project 3): task.view/task.complete match milestone.complete's
        // RECORD scope set (an assignee finishes their own work); task.manage and
        // comment.create are ORG_SCOPES only -- comment.create deliberately excludes
        // ASSIGNED (design spec 6.1), so a template holding case.view at ASSIGNED
        // cannot be granted comment.create at all, only task.view. Both resource
        // types need scoping/TaskDescriptor and scoping/CommentDescriptor, added in
        // the same commit as these entries so DescriptorRegistry.validate() does not
        // refuse to start (see the plan's Task 13, whose own text already says so).
        add(TASK_VIEW,               "task",    "task",            "View tasks",                    RECORD);
        add(TASK_MANAGE,             "task",    "task",            "Create, edit, assign and cancel tasks", ORG_SCOPES);
        add(TASK_COMPLETE,           "task",    "task",            "Transition a task's status",    RECORD);
        add(COMMENT_CREATE,          "task",    "comment",         "Post a comment",                ORG_SCOPES);
        // Task 11 (sub-project 3A): programme.view/programme.manage need
        // scoping/ProgrammeDescriptor, added in the same commit as these entries so
        // DescriptorRegistry.validate() does not refuse to start. programme.create
        // is ALL-only for the same reason customer.create and case.create are --
        // there is no programme yet to scope a create permission against.
        add(PROGRAMME_VIEW,          "programme", "programme",     "View programmes",               RECORD);
        add(PROGRAMME_CREATE,        "programme", null,            "Create a programme for a customer", ALL_ONLY);
        add(PROGRAMME_MANAGE,        "programme", "programme",     "Edit a programme, its journeys and its participants", ORG_SCOPES);
    }

    /**
     * CUSTOMER_CREATE is ALL-only on purpose: creation has no existing record to
     * scope against, so a narrower scope would have nothing to evaluate against
     * and would read as a restriction while imposing none.
     *
     * CASE_CREATE is ALL-only for the same reason CUSTOMER_CREATE is: there is no
     * case yet to scope against. It is not a hole -- creation resolves the target
     * customer through CustomerDirectory under customer.view, so a Sales
     * Representative holding customer.view at ASSIGNED can only open a case on a
     * customer they own. Authority to create is bounded by what you can see.
     *
     * MILESTONE_FORCE_APPROVE is ALL-only because Q5 puts that authority strictly
     * above Project Manager. A scoped version would let a TEAM-scoped holder
     * approve their own team's forcings, which is the single thing the approval
     * flow exists to prevent.
     */
    private static void add(String key, String category, String resourceType,
                            String description, Set<Scope> scopes) {
        BY_KEY.put(key, new Permission(key, category, resourceType, description, Set.copyOf(scopes)));
    }

    private PermissionCatalog() {}

    public static Collection<Permission> all() { return List.copyOf(BY_KEY.values()); }

    public static Optional<Permission> byKey(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }

    /**
     * Deny-by-default on both axes: an unknown key returns false rather than
     * throwing, and a known key returns false for any scope it does not list. A
     * caller that mistypes a key gets "no", never an accidental yes.
     */
    public static boolean allows(String key, Scope scope) {
        return byKey(key).map(p -> p.allowedScopes().contains(scope)).orElse(false);
    }
}
