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
        // TASK_VIEW only, no COMMENT_CREATE: this template's CASE_VIEW is ASSIGNED,
        // and comment.create is not catalogued at ASSIGNED (design spec 6.1) -- there
        // is no valid scope at which this template could hold it.
        // DOCUMENT_VIEW at ASSIGNED (Task 11, sub-project 4): matches this
        // template's own CASE_VIEW scope, the same "read at the scope you already
        // hold case.view at" pattern every template below follows. No
        // DOCUMENT_UPLOAD -- a Sales Representative does not deliver, so nothing
        // in the design spec's own seeding table (6.2) grants it here.
        new RoleTemplate("Sales Representative", "Owns prospects and new customers", Map.ofEntries(
            entry(CUSTOMER_VIEW, ASSIGNED), entry(CUSTOMER_CREATE, ALL), entry(CUSTOMER_EDIT, ASSIGNED),
            entry(CONTACT_VIEW, ASSIGNED), entry(CONTACT_MANAGE, ASSIGNED), entry(INVITATION_SEND, ASSIGNED),
            entry(WORKFLOW_VIEW, ALL), entry(CASE_VIEW, ASSIGNED), entry(CASE_CREATE, ALL), entry(TASK_VIEW, ASSIGNED),
            entry(DOCUMENT_VIEW, ASSIGNED))),

        // Map.ofEntries, not Map.of: twelve grants crosses Map.of's ten-pair ceiling.
        // PLAN_APPROVE_SCHEDULE at DEPARTMENT (Task 23, sub-project 3A gate 2): the
        // Account Manager owns the ongoing customer relationship, so recording the
        // customer's decision on a schedule revision belongs here rather than only
        // on Administrator.
        // DOCUMENT_VIEW/UPLOAD/MANAGE/SHARE/REQUEST all at TEAM (Task 11,
        // sub-project 4): matches this template's own CASE_VIEW/CASE_EDIT scope --
        // the relationship owner reads, uploads, retargets, shares and requests
        // documents on the cases their team runs. No DOCUMENT_REVIEW: design spec
        // 6.2's own seeding table reserves that to Legal/Finance/Compliance only.
        new RoleTemplate("Account Manager", "Owns ongoing customer relationships", Map.ofEntries(
            entry(CUSTOMER_VIEW, TEAM), entry(CUSTOMER_EDIT, TEAM), entry(CONTACT_VIEW, TEAM),
            entry(CONTACT_MANAGE, TEAM), entry(INVITATION_SEND, TEAM), entry(USER_VIEW, TEAM),
            entry(WORKFLOW_VIEW, ALL), entry(CASE_VIEW, TEAM), entry(CASE_EDIT, TEAM),
            entry(TASK_VIEW, TEAM), entry(COMMENT_CREATE, TEAM),
            entry(PLAN_APPROVE_SCHEDULE, DEPARTMENT),
            entry(DOCUMENT_VIEW, TEAM), entry(DOCUMENT_UPLOAD, TEAM), entry(DOCUMENT_MANAGE, TEAM),
            entry(DOCUMENT_SHARE, TEAM), entry(DOCUMENT_REQUEST, TEAM))),

        // Map.ofEntries, not Map.of: twenty grants crosses Map.of's ten-pair ceiling.
        // TASK_MANAGE at TEAM (sub-project 3A Phase 1 Task 8): this template already
        // holds TASK_VIEW/TASK_COMPLETE at TEAM -- a role that can complete a task but
        // not create one, add a checklist item, or reassign it is incoherent.
        // PLAN_ISSUE at TEAM (Task 23, sub-project 3A gate 2): the Project Manager
        // coordinates delivery day to day, so issuing a schedule revision for a
        // journey they run belongs here rather than only on Administrator.
        // PROGRAMME_VIEW/PROGRAMME_MANAGE at TEAM (Task 35, sub-project 3A close-out
        // role review): this template already holds CASE_EDIT/CASE_ADVANCE/
        // MILESTONE_EDIT at TEAM -- coordinating the parallel journeys a programme
        // groups for one customer is the same day-to-day delivery responsibility as
        // coordinating one journey, just one level up. Account Manager (above) was
        // considered too -- it owns the ongoing relationship and could plausibly view
        // a programme's rollup -- but PROGRAMME_MANAGE's actual shape ("edit a
        // programme, its journeys and its participants") is delivery orchestration,
        // not relationship ownership, so it belongs on the template that already does
        // that work at this scope; not split across two templates without a second
        // permission to tell VIEW and MANAGE apart. Both scopes are already exercised
        // by an existing narrowest-scope write test (TEAM,
        // ProgrammeMembershipServiceTest.aTeamScopedProgrammeManageHolderCanAddAParticipantWithinTheirOwnScope)
        // and an existing ASSIGNED-scope read test (ProgrammeScopeTest) -- CLAUDE.md's
        // "Working conventions" requirement was already met before this grant existed.
        // DOCUMENT_VIEW/UPLOAD/MANAGE/SHARE/REQUEST all at TEAM (Task 11,
        // sub-project 4): the day-to-day delivery coordinator needs the full
        // non-review document lifecycle on the cases they run, the same TEAM
        // scope every other grant here already sits at. No DOCUMENT_REVIEW, for
        // the same reason Account Manager has none -- design spec 6.2 reserves
        // it to Legal/Finance/Compliance.
        new RoleTemplate("Project Manager", "Coordinates onboarding delivery", Map.ofEntries(
            entry(CUSTOMER_VIEW, TEAM), entry(CUSTOMER_EDIT, TEAM), entry(CONTACT_VIEW, TEAM),
            entry(INVITATION_SEND, TEAM), entry(USER_VIEW, TEAM), entry(AUDIT_VIEW, TEAM),
            entry(WORKFLOW_VIEW, ALL),
            entry(CASE_VIEW, TEAM), entry(CASE_EDIT, TEAM), entry(CASE_ADVANCE, TEAM), entry(CASE_HOLD, TEAM),
            entry(MILESTONE_EDIT, TEAM), entry(MILESTONE_COMPLETE, TEAM),
            entry(MILESTONE_REOPEN, TEAM), entry(MILESTONE_FORCE_COMPLETE, TEAM),
            entry(REQUIREMENT_WAIVE, TEAM),
            entry(TASK_VIEW, TEAM), entry(TASK_MANAGE, TEAM), entry(TASK_COMPLETE, TEAM),
            entry(COMMENT_CREATE, TEAM),
            entry(PLAN_ISSUE, TEAM),
            entry(PROGRAMME_VIEW, TEAM), entry(PROGRAMME_MANAGE, TEAM),
            entry(DOCUMENT_VIEW, TEAM), entry(DOCUMENT_UPLOAD, TEAM), entry(DOCUMENT_MANAGE, TEAM),
            entry(DOCUMENT_SHARE, TEAM), entry(DOCUMENT_REQUEST, TEAM))),

        // TASK_COMPLETE joins MILESTONE_COMPLETE at the same ASSIGNED scope (spec
        // 5.2); no COMMENT_CREATE, for the same reason Sales Representative has
        // none -- CASE_VIEW here is ASSIGNED, and comment.create has no ASSIGNED scope.
        // DOCUMENT_VIEW and DOCUMENT_UPLOAD both at ASSIGNED (Task 11, sub-project
        // 4): design spec 6.2's own seeding table is the one place an external
        // delivery role gets upload, matching the personal, uploaded-by-mediated
        // ASSIGNED shape DocumentDescriptor resolves.
        new RoleTemplate("Service Provider", "Delivers technical services", Map.of(
            CUSTOMER_VIEW, ASSIGNED, CONTACT_VIEW, ASSIGNED, WORKFLOW_VIEW, ALL,
            CASE_VIEW, ASSIGNED, MILESTONE_COMPLETE, ASSIGNED,
            TASK_VIEW, ASSIGNED, TASK_COMPLETE, ASSIGNED,
            DOCUMENT_VIEW, ASSIGNED, DOCUMENT_UPLOAD, ASSIGNED)),

        // DOCUMENT_VIEW at ASSIGNED (Task 11, sub-project 4): matches this
        // template's own CASE_VIEW scope. No DOCUMENT_UPLOAD -- unlike Service
        // Provider, design spec 6.2's seeding table does not grant it here.
        new RoleTemplate("Business Partner", "External delivery partner", Map.of(
            CUSTOMER_VIEW, ASSIGNED, CONTACT_VIEW, ASSIGNED, WORKFLOW_VIEW, ALL,
            CASE_VIEW, ASSIGNED, MILESTONE_COMPLETE, ASSIGNED,
            TASK_VIEW, ASSIGNED, TASK_COMPLETE, ASSIGNED,
            DOCUMENT_VIEW, ASSIGNED)),

        // Map.ofEntries, not Map.of: twelve grants crosses Map.of's ten-pair ceiling.
        // APPROVAL_DECIDE at DEPARTMENT (sub-project 3A Phase 1 Task 8): the
        // department-lead-shaped template -- deciding a stage-exit approval no
        // longer requires the tenant's widest role. Not MILESTONE_FORCE_APPROVE,
        // which is ALL-only in the catalog itself (Q5) and cannot be narrower.
        // DOCUMENT_VIEW/UPLOAD/MANAGE/REQUEST all at DEPARTMENT (Task 11,
        // sub-project 4): matches this template's own CASE_VIEW/CASE_EDIT scope.
        // No DOCUMENT_SHARE and no DOCUMENT_REVIEW -- design spec 6.2's own
        // seeding table does not grant either here.
        new RoleTemplate("Operations", "Runs day-to-day onboarding operations", Map.ofEntries(
            entry(CUSTOMER_VIEW, DEPARTMENT), entry(CUSTOMER_EDIT, DEPARTMENT),
            entry(CONTACT_VIEW, DEPARTMENT), entry(USER_VIEW, DEPARTMENT), entry(WORKFLOW_VIEW, ALL),
            entry(CASE_VIEW, DEPARTMENT), entry(CASE_EDIT, DEPARTMENT), entry(MILESTONE_COMPLETE, DEPARTMENT),
            entry(APPROVAL_DECIDE, DEPARTMENT),
            entry(TASK_VIEW, DEPARTMENT), entry(TASK_COMPLETE, DEPARTMENT), entry(COMMENT_CREATE, DEPARTMENT),
            entry(DOCUMENT_VIEW, DEPARTMENT), entry(DOCUMENT_UPLOAD, DEPARTMENT),
            entry(DOCUMENT_MANAGE, DEPARTMENT), entry(DOCUMENT_REQUEST, DEPARTMENT))),

        // Map.ofEntries, not Map.of: eleven grants crosses Map.of's ten-pair ceiling.
        // DOCUMENT_VIEW and DOCUMENT_REVIEW both at ALL (Task 11, sub-project 4):
        // matches this template's own CASE_VIEW scope, and Q9's own department
        // list names Legal by name for document review.
        new RoleTemplate("Legal", "Reviews agreements and legal requirements", Map.ofEntries(
            entry(CUSTOMER_VIEW, ALL), entry(CONTACT_VIEW, ALL), entry(AUDIT_VIEW, ALL), entry(WORKFLOW_VIEW, ALL),
            entry(CASE_VIEW, ALL), entry(MILESTONE_COMPLETE, ALL),
            entry(TASK_VIEW, ALL), entry(TASK_COMPLETE, ALL), entry(COMMENT_CREATE, ALL),
            entry(DOCUMENT_VIEW, ALL), entry(DOCUMENT_REVIEW, ALL))),

        // DOCUMENT_VIEW and DOCUMENT_REVIEW both at ALL (Task 11, sub-project 4):
        // same reasoning as Legal above -- Q9 names Finance too.
        new RoleTemplate("Finance", "Handles billing and financial verification", Map.of(
            CUSTOMER_VIEW, ALL, CONTACT_VIEW, ALL, WORKFLOW_VIEW, ALL,
            CASE_VIEW, ALL, MILESTONE_COMPLETE, ALL,
            TASK_VIEW, ALL, TASK_COMPLETE, ALL, COMMENT_CREATE, ALL,
            DOCUMENT_VIEW, ALL, DOCUMENT_REVIEW, ALL)),

        // DOCUMENT_VIEW and DOCUMENT_UPLOAD both at TEAM (Task 11, sub-project 4):
        // matches this template's own CASE_VIEW scope. No DOCUMENT_REVIEW --
        // design spec 6.2 reserves that to Legal/Finance/Compliance, not Technical.
        new RoleTemplate("Technical", "Performs technical setup and testing", Map.of(
            CUSTOMER_VIEW, TEAM, CONTACT_VIEW, TEAM, WORKFLOW_VIEW, ALL,
            CASE_VIEW, TEAM, MILESTONE_COMPLETE, TEAM,
            TASK_VIEW, TEAM, TASK_COMPLETE, TEAM, COMMENT_CREATE, TEAM,
            DOCUMENT_VIEW, TEAM, DOCUMENT_UPLOAD, TEAM)),

        // Map.ofEntries, not Map.of: eleven grants crosses Map.of's ten-pair ceiling.
        // DOCUMENT_VIEW and DOCUMENT_REVIEW both at ALL (Task 11, sub-project 4):
        // same reasoning as Legal/Finance above -- Q9 names Compliance too.
        new RoleTemplate("Compliance", "Verifies KYC and regulatory requirements", Map.ofEntries(
            entry(CUSTOMER_VIEW, ALL), entry(CONTACT_VIEW, ALL), entry(AUDIT_VIEW, ALL), entry(WORKFLOW_VIEW, ALL),
            entry(CASE_VIEW, ALL), entry(MILESTONE_COMPLETE, ALL),
            entry(TASK_VIEW, ALL), entry(TASK_COMPLETE, ALL), entry(COMMENT_CREATE, ALL),
            entry(DOCUMENT_VIEW, ALL), entry(DOCUMENT_REVIEW, ALL))),

        // DOCUMENT_VIEW at TEAM (Task 11, sub-project 4): matches this template's
        // own CASE_VIEW scope. No upload/manage/review/share/request -- design
        // spec 6.2's seeding table does not grant Support any of the other five.
        new RoleTemplate("Support", "Assists customers post-activation", Map.of(
            CUSTOMER_VIEW, TEAM, CONTACT_VIEW, TEAM, WORKFLOW_VIEW, ALL,
            CASE_VIEW, TEAM, MILESTONE_COMPLETE, TEAM,
            TASK_VIEW, TEAM, TASK_COMPLETE, TEAM, COMMENT_CREATE, TEAM,
            DOCUMENT_VIEW, TEAM)),

        // Map.ofEntries, not Map.of: this covers the whole 31-permission catalog, and
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
            entry(WORKFLOW_VIEW, ALL), entry(WORKFLOW_MANAGE, ALL),
            // Journey (Task 11): Administrator alone holds CASE_MIGRATE and
            // MILESTONE_FORCE_APPROVE -- see PermissionCatalog for why both are
            // ALL-only, not just Administrator-seeded.
            entry(CASE_VIEW, ALL), entry(CASE_CREATE, ALL), entry(CASE_EDIT, ALL),
            entry(CASE_ADVANCE, ALL), entry(CASE_HOLD, ALL), entry(CASE_MIGRATE, ALL),
            entry(MILESTONE_EDIT, ALL), entry(MILESTONE_COMPLETE, ALL),
            entry(MILESTONE_REOPEN, ALL), entry(MILESTONE_FORCE_COMPLETE, ALL),
            entry(MILESTONE_FORCE_APPROVE, ALL), entry(REQUIREMENT_WAIVE, ALL),
            entry(APPROVAL_DECIDE, ALL),
            // Tasks (Task 12): TASK_MANAGE is seeded Administrator-only, the same
            // precedent ROLE_MANAGE and WORKFLOW_MANAGE already set -- a tenant
            // cannot escalate task-reassignment authority through any other seeded
            // role. TASK_VIEW/TASK_COMPLETE/COMMENT_CREATE follow from
            // Administrator already holding CASE_VIEW and MILESTONE_COMPLETE at ALL.
            entry(TASK_VIEW, ALL), entry(TASK_MANAGE, ALL),
            entry(TASK_COMPLETE, ALL), entry(COMMENT_CREATE, ALL),
            // Programmes (sub-project 3A Task 11; role review closed at Task 35):
            // seeded here purely because RoleTemplateValidityTest.administratorGrants
            // EveryPermissionInTheCatalog requires Administrator to cover the whole
            // catalog -- Project Manager above is what actually closes
            // RoleTemplateCoverageTest for programme.view/programme.manage.
            // PROGRAMME_CREATE stays ALL-only in the catalog itself (no programme yet
            // to scope a create permission against, the same reasoning as
            // CUSTOMER_CREATE/CASE_CREATE), so it is not a coverage-test candidate.
            entry(PROGRAMME_VIEW, ALL), entry(PROGRAMME_CREATE, ALL), entry(PROGRAMME_MANAGE, ALL),
            // Task 19 (sub-project 3A): plan.approve_shape is ALL-only in the
            // catalog itself (a workflow version has no narrower scope to resolve
            // against), so it is not a RoleTemplateCoverageTest candidate the way
            // programme.manage is -- that guard only flags permissions catalogued
            // at more than one scope. It is seeded here only because
            // RoleTemplateValidityTest.administratorGrantsEveryPermissionInTheCatalog
            // requires Administrator to cover the whole catalog; no other template
            // is expected to hold it until a later task decides otherwise.
            entry(PLAN_APPROVE_SHAPE, ALL),
            // Task 23 (sub-project 3A): gate 2's two permissions, seeded here purely
            // because RoleTemplateValidityTest.administratorGrantsEveryPermissionInTheCatalog
            // requires Administrator to cover the whole catalog -- Project Manager and
            // Account Manager above are what actually closes RoleTemplateCoverageTest
            // for these two keys.
            entry(PLAN_ISSUE, ALL), entry(PLAN_APPROVE_SCHEDULE, ALL),
            // Task 3 (sub-project 4) catalogued the first four of these six
            // ALL-only, with no document entity yet to widen against; Task 11
            // widens all six to RECORD/ORG_SCOPES (see PermissionCatalog) and
            // seeds Account Manager/Project Manager/Operations/Legal/Finance/
            // Technical/Compliance/Support/Sales Representative/Service Provider/
            // Business Partner across them above, which is what actually closes
            // RoleTemplateCoverageTest for these six keys. Seeded here purely
            // because RoleTemplateValidityTest.administratorGrantsEveryPermissionInTheCatalog
            // requires Administrator to cover the whole catalog.
            entry(DOCUMENT_VIEW, ALL), entry(DOCUMENT_UPLOAD, ALL),
            entry(DOCUMENT_MANAGE, ALL), entry(DOCUMENT_REVIEW, ALL),
            entry(DOCUMENT_SHARE, ALL), entry(DOCUMENT_REQUEST, ALL)))
    );

    private RoleTemplates() {}

    public static List<RoleTemplate> all() { return TEMPLATES; }
}
