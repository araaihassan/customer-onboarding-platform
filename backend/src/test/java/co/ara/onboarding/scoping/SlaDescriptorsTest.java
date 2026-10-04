package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.sla.*;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** sla_clock and escalation inherit scope from their case (Task 7). Narrowest scope first. */
class SlaDescriptorsTest extends PostgresTestBase {

    @Autowired AuthorizedQuery authorizedQuery;
    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired SlaClockRepository slaClocks;
    @Autowired EscalationRepository escalations;
    @Autowired SlaPauseRepository slaPauses;

    private SlaPause pause(UUID tenant, SlaClock clock) {
        SlaPause p = new SlaPause();
        p.setId(Uuid7.generate());
        p.setTenantId(tenant);
        p.setClockId(clock.getId());
        p.setReason(PauseReason.CASE_HOLD);
        p.setStartedAt(Instant.now());
        return slaPauses.saveAndFlush(p);
    }

    /** sla_pause inherits scope two levels deep: pause -> clock -> case. */
    @Test
    void aTeamScopedHolderSeesOnlyPausesOfTheirTeamsClocksAndNoTeamSeesNothing() {
        UUID tenant = fixture.createTenant("sla-pause");
        UUID[] mine = new UUID[1];
        UUID[] user = new UUID[2];
        fixture.runAs(tenant, () -> {
            UUID team = fixture.createTeam(tenant, "A");
            UUID other = fixture.createTeam(tenant, "B");
            user[0] = holder(tenant, "t@sla-pause.example", Scope.TEAM);
            fixture.addToTeam(tenant, user[0], team);
            user[1] = fixture.createUser(tenant, "n@sla-pause.example");
            roles.assignRole(user[1], roles.createRole("No Team Sla", "", Map.of(PermissionKeys.SLA_VIEW, Scope.TEAM)));
            mine[0] = pause(tenant, clock(tenant, journey.newCase(tenant, null, null, team))).getId();
            pause(tenant, clock(tenant, journey.newCase(tenant, null, null, other)));
        });
        for (int i = 0; i < 2; i++) {
            var out = new java.util.concurrent.atomic.AtomicReference<List<UUID>>();
            fixture.runAsUser(tenant, user[i], () -> out.set(authorizedQuery
                    .findAll(slaPauses, SlaPause.class, PermissionKeys.SLA_VIEW, null, Pageable.unpaged())
                    .map(SlaPause::getId).getContent()));
            if (i == 0) assertThat(out.get()).containsExactly(mine[0]); else assertThat(out.get()).isEmpty();
        }
    }

    private SlaClock clock(UUID tenant, Case c) {
        SlaClock s = new SlaClock();
        s.setId(Uuid7.generate());
        s.setTenantId(tenant);
        s.setCaseId(c.getId());
        s.setStageId(journey.newStage(tenant, c.getVersionId()));
        s.setTargetDays(5);
        s.setStartedAt(Instant.now());
        return slaClocks.saveAndFlush(s);
    }

    private Escalation escalation(UUID tenant, Case c) {
        Escalation e = new Escalation();
        e.setId(Uuid7.generate());
        e.setTenantId(tenant);
        e.setSubjectType(EscalationSubject.MILESTONE);
        e.setSubjectId(Uuid7.generate());
        e.setCaseId(c.getId());
        e.setRoute(EscalationRoute.MANAGER);
        e.setDueDateAtEscalation(LocalDate.now());
        e.setOverdueDays(1);
        e.setEscalatedAt(Instant.now());
        return escalations.saveAndFlush(e);
    }

    private UUID holder(UUID tenant, String email, Scope scope) {
        UUID user = fixture.createUser(tenant, email);
        UUID role = roles.createRole("Sla Viewer " + scope, "", Map.of(PermissionKeys.SLA_VIEW, scope));
        roles.assignRole(user, role);
        return user;
    }

    private List<UUID> clockIds(UUID tenant, UUID user) {
        var out = new java.util.concurrent.atomic.AtomicReference<List<UUID>>();
        fixture.runAsUser(tenant, user, () -> out.set(authorizedQuery
                .findAll(slaClocks, SlaClock.class, PermissionKeys.SLA_VIEW, null, Pageable.unpaged())
                .map(SlaClock::getId).getContent()));
        return out.get();
    }

    private List<UUID> escalationIds(UUID tenant, UUID user) {
        var out = new java.util.concurrent.atomic.AtomicReference<List<UUID>>();
        fixture.runAsUser(tenant, user, () -> out.set(authorizedQuery
                .findAll(escalations, Escalation.class, PermissionKeys.SLA_VIEW, null, Pageable.unpaged())
                .map(Escalation::getId).getContent()));
        return out.get();
    }

    @Test
    void aTeamScopedHolderSeesOnlyTheirTeamsClocksAndEscalations() {
        UUID tenant = fixture.createTenant("sla-team");
        UUID[] mine = new UUID[2];
        UUID[] user = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID team = fixture.createTeam(tenant, "A");
            UUID other = fixture.createTeam(tenant, "B");
            user[0] = holder(tenant, "t@sla-team.example", Scope.TEAM);
            fixture.addToTeam(tenant, user[0], team);
            Case own = journey.newCase(tenant, null, null, team);
            Case foreign = journey.newCase(tenant, null, null, other);
            mine[0] = clock(tenant, own).getId();
            clock(tenant, foreign);
            mine[1] = escalation(tenant, own).getId();
            escalation(tenant, foreign);
        });
        assertThat(clockIds(tenant, user[0])).containsExactly(mine[0]);
        assertThat(escalationIds(tenant, user[0])).containsExactly(mine[1]);
    }

    @Test
    void aDepartmentScopedHolderSeesOnlyTheirDepartmentsClocksAndEscalations() {
        UUID tenant = fixture.createTenant("sla-dept");
        UUID[] mine = new UUID[2];
        UUID[] user = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID dept = fixture.createDepartment(tenant, "A");
            UUID other = fixture.createDepartment(tenant, "B");
            user[0] = fixture.createUserInDepartment(tenant, "d@sla-dept.example", dept);
            UUID role = roles.createRole("Dept Sla", "", Map.of(PermissionKeys.SLA_VIEW, Scope.DEPARTMENT));
            roles.assignRole(user[0], role);
            Case own = journey.newCase(tenant, null, dept, null);
            Case foreign = journey.newCase(tenant, null, other, null);
            mine[0] = clock(tenant, own).getId();
            clock(tenant, foreign);
            mine[1] = escalation(tenant, own).getId();
            escalation(tenant, foreign);
        });
        assertThat(clockIds(tenant, user[0])).containsExactly(mine[0]);
        assertThat(escalationIds(tenant, user[0])).containsExactly(mine[1]);
    }

    @Test
    void aTeamHolderWithNoTeamsSeesNothing() {
        UUID tenant = fixture.createTenant("sla-noteam");
        UUID[] user = new UUID[1];
        fixture.runAs(tenant, () -> {
            user[0] = holder(tenant, "n@sla-noteam.example", Scope.TEAM);
            Case c = journey.newCase(tenant, null, null, fixture.createTeam(tenant, "X"));
            clock(tenant, c);
            escalation(tenant, c);
        });
        assertThat(clockIds(tenant, user[0])).isEmpty();
        assertThat(escalationIds(tenant, user[0])).isEmpty();
    }

    @Test
    void aHolderWithNoGrantSeesNothing() {
        UUID tenant = fixture.createTenant("sla-nogrant");
        UUID[] user = new UUID[1];
        fixture.runAs(tenant, () -> {
            user[0] = fixture.createUser(tenant, "g@sla-nogrant.example");
            Case c = journey.newCase(tenant);
            clock(tenant, c);
            escalation(tenant, c);
        });
        assertThat(clockIds(tenant, user[0])).isEmpty();
        assertThat(escalationIds(tenant, user[0])).isEmpty();
    }
}
