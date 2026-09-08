package co.ara.onboarding.task;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 4 (sub-project 3A): design spec Sec8.2 specified "Do now, sorted by due
 * date" for the My Work board, but myWork/forCase both passed
 * Pageable.unpaged() with no Sort at all -- so the most important column had
 * no meaningful ordering, and an overdue item could render below one due next
 * month.
 *
 * The point of the fixture below is specifically the undated task: Postgres's
 * ASC default is NULLS FIRST, which would put the undated task ABOVE the
 * overdue one -- the exact inversion this test exists to catch. Seeding order
 * is deliberately NOT date order (30-days task seeded first, overdue second,
 * ...), so a query with no ORDER BY at all -- which on a small, single-page
 * result set Postgres/Hibernate often returns in physical (insertion) order
 * -- would fail this assertion too, not just pass vacuously.
 */
class TaskOrderingTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired TaskService tasks;
    @Autowired RoleService roles;

    @Test
    void myWorkReturnsDueSoonestFirstWithUndatedLast() {
        UUID tenant = fixture.createTenant("task-order-mywork");
        var pm = new UUID[1];
        var caseId = new UUID[1];

        fixture.runAs(tenant, () -> {
            pm[0] = fixture.createUser(tenant, "pm@example.com");
            grant(pm[0], Map.of(PermissionKeys.TASK_VIEW, Scope.ALL));

            caseId[0] = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId[0]);

            seedTask(caseId[0], milestoneId, pm[0], "due in 30 days", LocalDate.now().plusDays(30));
            seedTask(caseId[0], milestoneId, pm[0], "overdue", LocalDate.now().minusDays(2));
            seedTask(caseId[0], milestoneId, pm[0], "no due date", null);
            seedTask(caseId[0], milestoneId, pm[0], "due tomorrow", LocalDate.now().plusDays(1));
        });

        var titles = new List[1];
        fixture.runAsUser(tenant, pm[0], () ->
                titles[0] = tasks.myWork("do_now").stream().map(TaskView::title).toList());

        assertThat(titles[0]).containsExactly(
                "overdue", "due tomorrow", "due in 30 days", "no due date");
    }

    @Test
    void forCaseReturnsDueSoonestFirstWithUndatedLast() {
        UUID tenant = fixture.createTenant("task-order-forcase");
        var caseId = new UUID[1];
        var titles = new List[1];

        fixture.runAs(tenant, () -> {
            caseId[0] = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId[0]);

            seedTask(caseId[0], milestoneId, null, "due in 30 days", LocalDate.now().plusDays(30));
            seedTask(caseId[0], milestoneId, null, "overdue", LocalDate.now().minusDays(2));
            seedTask(caseId[0], milestoneId, null, "no due date", null);
            seedTask(caseId[0], milestoneId, null, "due tomorrow", LocalDate.now().plusDays(1));

            titles[0] = tasks.forCase(caseId[0]).stream().map(TaskView::title).toList();
        });

        assertThat(titles[0]).containsExactly(
                "overdue", "due tomorrow", "due in 30 days", "no due date");
    }

    /**
     * The createdAt tiebreak: two tasks sharing a due date have no defined
     * relative order from dueDate alone, so without a secondary key the pair
     * could swap between requests (query-plan/storage-layout dependent, not
     * something a single assertion would reliably catch). Asserting the SAME
     * order across several independent calls -- not just once -- is what
     * actually exercises "deterministic" rather than "happened to come back
     * right this time".
     */
    @Test
    void tasksDueTheSameDayTiebreakOnCreationOrderAcrossRepeatedReads() {
        UUID tenant = fixture.createTenant("task-order-tiebreak");
        var caseId = new UUID[1];
        LocalDate sameDay = LocalDate.now().plusDays(5);

        fixture.runAs(tenant, () -> {
            caseId[0] = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId[0]);
            seedTask(caseId[0], milestoneId, null, "created first", sameDay);
            seedTask(caseId[0], milestoneId, null, "created second", sameDay);
        });

        for (int i = 0; i < 5; i++) {
            var titles = new List[1];
            fixture.runAs(tenant, () ->
                    titles[0] = tasks.forCase(caseId[0]).stream().map(TaskView::title).toList());
            assertThat(titles[0]).containsExactly("created first", "created second");
        }
    }

    private void seedTask(UUID caseId, UUID milestoneId, UUID assigneeId, String title, LocalDate dueDate) {
        tasks.create(caseId, new CreateTaskRequest(
                milestoneId, null, title, null, TaskPriority.MEDIUM, assigneeId, dueDate));
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
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
