package co.ara.onboarding.task;

import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 25: the two negatives that prove a record is unreachable/refused
 * regardless of whatever else is true about the caller -- a portal contact
 * with zero task.* authority, and the database's own second half of the
 * comment-resource-type gate. Neither adds new production logic; both prove
 * invariants Tasks 16/17/22/23 already built.
 */
class TaskIsolationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;

    /**
     * Spec §6.4. A portal contact may be a task's assignee, but holds no
     * task.* grant at all -- "absence of a grant is the denial" -- so the
     * task stays invisible to them until sub-project 7 gives the portal its
     * own surface.
     *
     * The brief's own draft predicted NoSuchElementException here (from
     * AuthorizedQuery/TaskDescriptor's scope resolution collapsing to
     * disjunction), and running it red first showed that prediction was
     * wrong: {@code PermissionGateAspect.enforce} answers only "does this
     * actor hold task.view at ANY scope" (its own javadoc's words) and throws
     * AccessDeniedException BEFORE AuthorizedQuery is ever reached -- the two
     * are deliberately separate layers, coarse gate first, record-level scope
     * second. This is not a new finding: it is the exact shape
     * {@code security.WriteScopeTest.anAnyStageStillRequiresThePermission}
     * already proves for milestone.complete ("An actor with no
     * milestone.complete grant at all is refused by the permission gate
     * itself, before write_scope is ever consulted"). Corrected to match that
     * established, already-correct precedent rather than the brief's guess.
     */
    @Test
    void aPortalAssigneeCannotSeeTheirOwnTask() {
        UUID tenant = fixture.createTenant("task-iso-portal");
        var taskId = new UUID[1];
        var portalUserId = new UUID[1];
        fixture.runAs(tenant, () -> {
            portalUserId[0] = fixture.createPortalUser(tenant, "portal@example.com").getId();
            UUID caseId = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId);
            taskId[0] = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, null, "Upload your documents", null,
                    TaskPriority.MEDIUM, portalUserId[0], null)).id();
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, portalUserId[0], () ->
                tasks.get(taskId[0])))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * The Java enum (CommentResourceType) makes an invalid resource_type
     * uncompilable through CommentService, so this bypasses it with a raw
     * insert -- proving comment_resource_type_ck (V16__task.sql) is a real,
     * independent second half of the gate and not decorative. Run inside
     * fixture.runAs so the insert satisfies the RLS WITH CHECK on tenant_id
     * and fails on the CHECK constraint specifically, not on a row-security
     * violation instead.
     */
    @Test
    void anUnlistedCommentResourceTypeIsRefusedByTheDatabase() {
        UUID tenant = fixture.createTenant("task-iso-comment-ck");
        var caseId = new UUID[1];
        var userId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = simpleCase(tenant);
            userId[0] = fixture.createUser(tenant, "author@example.com");
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> jdbc.update(
                "INSERT INTO comment (id,tenant_id,case_id,resource_type,resource_id," +
                "author_id,body,created_at,updated_at) VALUES (?,?,?,'document',?,?,?,now(),now())",
                Uuid7.generate(), tenant, caseId[0], caseId[0], userId[0], "x")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID simpleCase(UUID tenant) {
        UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
        return cases.create(new CreateCaseRequest(
                customerId, journey.publishedTemplate(), "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }

    private UUID firstMilestone(UUID caseId) {
        return cases.roadmap(caseId).stages().get(0).milestones().get(0).id();
    }
}
