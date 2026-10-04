package co.ara.onboarding.sla;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.security.SecurityTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The narrowest-scope negatives CLAUDE.md requires for a permission catalogued at several scopes
 * (sub-project 6 Task 22). sla.view resolves through the SLA_VIEW descriptors and case.view through
 * the case descriptor; the feed intersects them. Each scope is run three ways so each layer is proven
 * on its own: both at the narrow scope, case.view widened to ALL (only the sla_clock/escalation
 * descriptors can exclude the other cases), and sla.view widened to ALL (only case.view can).
 */
class SlaScopeTest extends SecurityTestBase {

    @Autowired SlaTestSupport sla;
    @Autowired SlaExceptionsService exceptions;
    @Autowired SlaClockService clockService;

    private enum Kind { TEAM, DEPARTMENT, ASSIGNED }

    private record Setup(UUID tenant, AppUser admin, AppUser viewer, UUID mine, UUID theirs) {}

    /**
     * One viewer (team T, department D) and two breached cases, each with a clock escalation. Mine
     * matches the viewer through exactly the given kind's relationship and no other; theirs through none.
     */
    private Setup setup(String slug, Kind kind, Scope slaScope, Scope caseScope) {
        UUID t = fixture.createTenant(slug);
        AppUser admin = fixture.createAdminUser(t, "a@" + slug + ".example");
        var viewer = new AtomicReference<AppUser>();
        var mine = new AtomicReference<UUID>();
        var theirs = new AtomicReference<UUID>();
        var otherUser = new AtomicReference<UUID>();
        var deptOfViewer = new AtomicReference<UUID>();
        fixture.runAs(t, () -> {
            UUID d = fixture.createDepartment(t, "D");
            UUID d2 = fixture.createDepartment(t, "D2");
            UUID team = fixture.createTeam(t, "T");
            UUID team2 = fixture.createTeam(t, "T2");
            AppUser v = fixture.createUserWithPassword(t, "v@" + slug + ".example", "long-enough-password");
            viewer.set(v);
            otherUser.set(fixture.createUser(t, "o@" + slug + ".example"));
            fixture.addToTeam(t, v.getId(), team);
            deptOfViewer.set(d);
            mine.set(sla.openFor(fixture.createCustomer(t, "Mine Co",
                    kind == Kind.ASSIGNED ? v.getId() : otherUser.get(),
                    kind == Kind.DEPARTMENT ? d : d2, kind == Kind.TEAM ? team : team2)));
            theirs.set(sla.openFor(fixture.createCustomer(t, "Theirs Co", otherUser.get(), d2, team2)));
            roles.assignRole(v.getId(), roles.createRole("Sla " + kind, "", Map.of(
                    PermissionKeys.SLA_VIEW, slaScope, PermissionKeys.CASE_VIEW, caseScope,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL, PermissionKeys.CUSTOMER_VIEW, Scope.ALL)));
        });
        // After the runAs transaction has committed: the owner connection cannot see an uncommitted row.
        ownerJdbc().update("update app_user set department_id = ? where id = ?",
                deptOfViewer.get(), viewer.get().getId());
        Instant now = clock.instant();
        for (UUID c : new UUID[]{mine.get(), theirs.get()}) {
            ownerJdbc().update("update sla_clock set breached_at = ? where case_id = ? and stopped_at is null",
                    Timestamp.from(now), c);
            Timestamp ts = Timestamp.from(now.minus(Duration.ofDays(1)));
            ownerJdbc().update("""
                    insert into escalation (id, tenant_id, subject_type, subject_id, case_id, route,
                        escalated_to_user_id, due_date_at_escalation, overdue_days, escalated_at, created_at, updated_at)
                    values (?, ?, 'SLA_CLOCK', ?, ?, 'MANAGER', ?, ?, 1, ?, ?, ?)""",
                    Uuid7.generate(), t, sla.openClockId(c), c, otherUser.get(), LocalDate.now(), ts, ts, ts);
        }
        return new Setup(t, admin, viewer.get(), mine.get(), theirs.get());
    }

    private ExceptionsView read(UUID t, UUID user) {
        var out = new AtomicReference<ExceptionsView>();
        fixture.runAsUser(t, user, () -> out.set(exceptions.exceptions()));
        return out.get();
    }

    private void run(String slug, Kind kind, Scope slaScope, Scope caseScope) {
        Setup s = setup(slug, kind, slaScope, caseScope);

        // Positive control: an administrator of the same tenant sees both, so the narrowing below is real.
        ExceptionsView all = read(s.tenant(), s.admin().getId());
        assertThat(all.breached()).extracting(ExceptionsView.Card::caseId)
                .containsExactlyInAnyOrder(s.mine(), s.theirs());
        assertThat(all.summary().breached()).isEqualTo(2);
        assertThat(all.summary().autoEscalated()).isEqualTo(2);

        ExceptionsView v = read(s.tenant(), s.viewer().getId());
        assertThat(v.breached()).extracting(ExceptionsView.Card::caseId).containsExactly(s.mine());
        assertThat(v.breached().get(0).escalations()).hasSize(1);
        assertThat(v.summary().breached()).isEqualTo(1);
        assertThat(v.summary().autoEscalated()).isEqualTo(1);
        assertThat(v.toString()).doesNotContain(s.theirs().toString()).doesNotContain("Theirs Co");
    }

    @Test void teamScopedSlaViewAndCaseView() { run("scope-team", Kind.TEAM, Scope.TEAM, Scope.TEAM); }

    @Test void departmentScopedSlaViewAndCaseView() {
        run("scope-dept", Kind.DEPARTMENT, Scope.DEPARTMENT, Scope.DEPARTMENT);
    }

    @Test void assignedScopedSlaViewAndCaseView() { run("scope-asg", Kind.ASSIGNED, Scope.ASSIGNED, Scope.ASSIGNED); }

    /** case.view at ALL cannot hide the other team's clock: the sla_clock and escalation descriptors alone must. */
    @Test void teamSlaViewAloneExcludesTheOtherTeamsClockAndEscalation() {
        run("scope-team-sla", Kind.TEAM, Scope.TEAM, Scope.ALL);
    }

    @Test void departmentSlaViewAloneExcludesTheOtherDepartmentsClockAndEscalation() {
        run("scope-dept-sla", Kind.DEPARTMENT, Scope.DEPARTMENT, Scope.ALL);
    }

    @Test void assignedSlaViewAloneExcludesAnUnassignedCasesClockAndEscalation() {
        run("scope-asg-sla", Kind.ASSIGNED, Scope.ASSIGNED, Scope.ALL);
    }

    /** sla.view at ALL cannot widen the feed past case.view's scope. */
    @Test void caseViewAloneNarrowsAnAllScopedSlaViewer() {
        run("scope-team-case", Kind.TEAM, Scope.ALL, Scope.TEAM);
        run("scope-dept-case", Kind.DEPARTMENT, Scope.ALL, Scope.DEPARTMENT);
        run("scope-asg-case", Kind.ASSIGNED, Scope.ALL, Scope.ASSIGNED);
    }

    @Test
    void aHolderOfSlaViewWithoutCaseViewSeesNoCards() {
        UUID t = fixture.createTenant("scope-nocase");
        var viewer = new AtomicReference<AppUser>();
        fixture.runAs(t, () -> {
            AppUser v = fixture.createUserWithPassword(t, "v@scope-nocase.example", "long-enough-password");
            viewer.set(v);
            roles.assignRole(v.getId(), roles.createRole("Sla Only", "", Map.of(
                    PermissionKeys.SLA_VIEW, Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL)));
        });
        UUID c = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 3, true));
        ownerJdbc().update("update sla_clock set breached_at = ? where case_id = ?",
                Timestamp.from(clock.instant()), c);
        ExceptionsView v = read(t, viewer.get().getId());
        assertThat(v.breached()).isEmpty();
        assertThat(v.dueToday()).isEmpty();
        assertThat(v.watch()).isEmpty();
        assertThat(v.summary().breached()).isZero();
    }

    /** The war-room batch read is gated on sla.view itself, not only reachable through the gated feed. */
    @Test
    void theBatchViewsReadRequiresSlaViewNotJustCaseView() {
        UUID t = fixture.createTenant("scope-views");
        var viewer = new AtomicReference<AppUser>();
        fixture.runAs(t, () -> {
            AppUser v = fixture.createUserWithPassword(t, "v@scope-views.example", "long-enough-password");
            viewer.set(v);
            roles.assignRole(v.getId(), roles.createRole("Case Only", "", Map.of(
                    PermissionKeys.CASE_VIEW, Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL)));
        });
        assertThatThrownBy(() -> fixture.runAsUser(t, viewer.get().getId(), () -> clockService.views(List.of())))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> fixture.runAsUser(t, viewer.get().getId(), () -> exceptions.exceptions()))
                .isInstanceOf(AccessDeniedException.class);
    }
}
