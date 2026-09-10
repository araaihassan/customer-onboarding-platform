package co.ara.onboarding.authz;

import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.customer.CustomerRepository;
import co.ara.onboarding.customer.CustomerStatus;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.task.Comment;
import co.ara.onboarding.task.CommentRepository;
import co.ara.onboarding.task.CommentResourceType;
import co.ara.onboarding.task.Task;
import co.ara.onboarding.task.TaskPriority;
import co.ara.onboarding.task.TaskRepository;
import co.ara.onboarding.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DescriptorRegistryTest extends PostgresTestBase {

    @Autowired DescriptorRegistry registry;
    @Autowired CustomerRepository customers;
    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journeyFixtures;
    @Autowired TaskRepository tasks;
    @Autowired CommentRepository comments;

    @Test
    void everyRecordScopedPermissionHasADescriptor() {
        // Passes only because all four descriptors are registered; this is the
        // same assertion the application makes at startup.
        registry.validate();
    }

    /**
     * Task 11 (sub-project 3A): programme.view/programme.manage are catalogued at
     * record scopes, so ProgrammeDescriptor must be registered or validate() refuses
     * startup naming resource type 'programme' -- confirmed genuinely red before
     * ProgrammeDescriptor existed.
     */
    @Test
    void everyRecordScopedResourceTypeHasADescriptor() {
        assertThatNoException().isThrownBy(() -> registry.validate());
        assertThat(registry.resourceTypes()).contains("programme");
    }

    /**
     * Task 23 (sub-project 3A gate 2): plan.issue/plan.approve_schedule are
     * RECORD-scoped on onboarding_case, which already has CaseDescriptor, so
     * validate() itself does not require PlanRevisionDescriptor or
     * PlanRevisionItemDescriptor -- they exist for AuthorizedQuery's entity-type
     * dispatch instead, the same reason CaseParticipantDescriptor and
     * CaseAttributeValueDescriptor were registered ahead of validate() ever
     * demanding them. Asserting their resourceType()s are registered is the only
     * way that reason would surface here rather than staying invisible until a
     * later service actually reads a PlanRevision/PlanRevisionItem row.
     */
    @Test
    void planRevisionAndPlanRevisionItemHaveDescriptorsForAuthorizedQueryDispatch() {
        assertThat(registry.forEntity(co.ara.onboarding.journey.PlanRevision.class)).isNotNull();
        assertThat(registry.forEntity(co.ara.onboarding.journey.PlanRevisionItem.class)).isNotNull();
        assertThat(registry.resourceTypes()).contains("plan_revision", "plan_revision_item");
    }

    @Test
    void missingDescriptorIsAStartupFailure() {
        DescriptorRegistry empty = new DescriptorRegistry(List.of());
        assertThatThrownBy(empty::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no ResourceAuthorizationDescriptor");
    }

    @Test
    void customerDeclaresOwnerOnlyForAssignedScope() {
        var descriptor = registry.forEntity(Customer.class);
        assertThat(descriptor.assignedRelationships())
                .containsExactly(RelationshipType.OWNER);
    }

    /**
     * Not in the plan. The descriptors' central safety claim is that an actor with
     * no department matches nothing rather than everything — fail closed, never
     * open — and nothing exercised the Specifications at all, so a departmentScope
     * that returned a conjunction (match-all) instead of a disjunction would have
     * passed every test above.
     *
     * The positive half matters just as much: a predicate that matches nothing
     * unconditionally would also satisfy the fail-closed assertion on its own.
     */
    @Test
    void departmentScopeMatchesTheDepartmentAndFailsClosedWithoutOne() {
        UUID tenant = fixture.createTenant("dept-scope");

        fixture.runAs(tenant, () -> {
            UUID owner = fixture.createUser(tenant, "owner@dept-scope.example");
            // A real department row: customer.owning_department_id is a foreign key, so
            // a synthetic UUID fails the constraint rather than the assertion.
            UUID department = fixture.createDepartment(tenant, "Onboarding");

            customers.save(customer(tenant, "In Department", owner, department));
            customers.save(customer(tenant, "No Department", owner, null));

            var descriptor = registry.forEntity(Customer.class);

            var inDepartment = descriptor.departmentScope(
                    new AuthContext(tenant, owner, UserType.INTERNAL, department, Set.of()));
            assertThat(customers.findAll(inDepartment))
                    .as("an actor in the department sees that department's records")
                    .extracting(Customer::getDisplayName)
                    .containsExactly("In Department");

            var noDepartment = descriptor.departmentScope(
                    new AuthContext(tenant, owner, UserType.INTERNAL, null, Set.of()));
            assertThat(customers.findAll(noDepartment))
                    .as("an actor with no department must match nothing, not everything")
                    .isEmpty();
        });
    }

    /**
     * Task 13 (pulled forward into Task 12's commit -- see PermissionCatalog's own
     * comment on TASK_VIEW/TASK_MANAGE/TASK_COMPLETE/COMMENT_CREATE: without these
     * two descriptors, DescriptorRegistry.validate() refuses application startup
     * the moment those four permissions are catalogued at a record scope, which
     * the plan's own Task 13 "Produces" line already says). ASSIGNED on a task is
     * personal, never team-mediated: an actor who merely shares the owning team
     * with the task must not match a task assigned to someone else.
     */
    @Test
    void taskAssignedScopeIsPersonalAndNotTeamMediated() {
        UUID tenant = fixture.createTenant("task-assigned-scope");

        fixture.runAs(tenant, () -> {
            UUID team = fixture.createTeam(tenant, "Onboarding Team");
            UUID actor = fixture.createUser(tenant, "actor@task-assigned-scope.example");
            UUID someoneElse = fixture.createUser(tenant, "someone@task-assigned-scope.example");
            fixture.addToTeam(tenant, actor, team);

            Case c = journeyFixtures.newCase(tenant, null, null, team);
            var milestone = journeyFixtures.newMilestone(tenant, c);

            Task task = new Task();
            task.setId(Uuid7.generate());
            task.setTenantId(tenant);
            task.setCaseId(c.getId());
            task.setMilestoneId(milestone.getId());
            task.setTitle("Fixture task");
            task.setPriority(TaskPriority.MEDIUM);
            task.setStatus(TaskStatus.PENDING);
            task.setAssigneeId(someoneElse);
            tasks.saveAndFlush(task);

            var descriptor = registry.forEntity(Task.class);
            var predicate = descriptor.assignedScope(
                    new AuthContext(tenant, actor, UserType.INTERNAL, null, Set.of(team)));

            assertThat(tasks.findAll(predicate))
                    .as("a team-owned task assigned to someone else must not match ASSIGNED for a mere teammate")
                    .isEmpty();
        });
    }

    @Test
    void bothDescriptorsFailClosedWithNoDepartmentAndNoTeams() {
        UUID tenant = fixture.createTenant("descriptor-fail-closed");

        fixture.runAs(tenant, () -> {
            UUID actor = fixture.createUser(tenant, "actor@descriptor-fail-closed.example");
            UUID department = fixture.createDepartment(tenant, "Ops");
            Case c = journeyFixtures.newCase(tenant, null, department, null);
            var milestone = journeyFixtures.newMilestone(tenant, c);

            Task task = new Task();
            task.setId(Uuid7.generate());
            task.setTenantId(tenant);
            task.setCaseId(c.getId());
            task.setMilestoneId(milestone.getId());
            task.setTitle("Fixture task");
            task.setPriority(TaskPriority.MEDIUM);
            task.setStatus(TaskStatus.PENDING);
            tasks.saveAndFlush(task);

            Comment comment = new Comment();
            comment.setId(Uuid7.generate());
            comment.setTenantId(tenant);
            comment.setCaseId(c.getId());
            comment.setResourceType(CommentResourceType.CASE);
            comment.setResourceId(c.getId());
            comment.setAuthorId(actor);
            comment.setBody("Fixture comment");
            comments.saveAndFlush(comment);

            var bare = new AuthContext(tenant, actor, UserType.INTERNAL, null, Set.of());

            var taskDescriptor = registry.forEntity(Task.class);
            assertThat(tasks.findAll(taskDescriptor.departmentScope(bare)))
                    .as("a task actor with no department must match nothing, not everything")
                    .isEmpty();

            var commentDescriptor = registry.forEntity(Comment.class);
            assertThat(comments.findAll(commentDescriptor.teamScope(bare)))
                    .as("a comment actor with no teams must match nothing, not everything")
                    .isEmpty();
        });
    }

    private static Customer customer(UUID tenant, String name, UUID owner, UUID department) {
        Customer c = new Customer();
        c.setId(Uuid7.generate());
        c.setTenantId(tenant);
        c.setLegalName(name + " Ltd");
        c.setDisplayName(name);
        c.setStatus(CustomerStatus.PROSPECT);
        c.setOwnerUserId(owner);
        c.setCreatedBy(owner);
        c.setOwningDepartmentId(department);
        return c;
    }
}
