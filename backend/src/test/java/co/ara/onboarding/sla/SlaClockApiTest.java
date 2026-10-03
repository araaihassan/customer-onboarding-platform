package co.ara.onboarding.sla;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.security.SecurityTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec 8: GET /cases/{id}/sla-clock. */
class SlaClockApiTest extends SecurityTestBase {

    @Autowired SlaTestSupport sla;
    @Autowired CaseService cases;
    @Autowired RequirementService requirements;

    private static String url(String slug, UUID caseId) {
        return "/api/t/" + slug + "/cases/" + caseId + "/sla-clock";
    }

    @Test
    void anAdministratorReadsTheCurrentClock() throws Exception {
        UUID t = fixture.createTenant("slaapi-run");
        AppUser admin = fixture.createAdminUser(t, "a@slaapi-run.example");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 3, true));
        mvc.perform(as(get(url("slaapi-run", caseId)), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.targetDays").value(3))
                .andExpect(jsonPath("$.calendarName", not(emptyOrNullString())));
    }

    @Test
    void theLastStoppedClockIsReturnedWhenNoneIsOpen() throws Exception {
        UUID t = fixture.createTenant("slaapi-met");
        AppUser admin = fixture.createAdminUser(t, "a@slaapi-met.example");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 3, true));
        fixture.runAs(t, () -> requirements.satisfy(sla.firstRequirementId(caseId), null, null));
        mvc.perform(as(get(url("slaapi-met", caseId)), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("MET"));
    }

    @Test
    void aCaseWithNoClockIs404() throws Exception {
        UUID t = fixture.createTenant("slaapi-none");
        AppUser admin = fixture.createAdminUser(t, "a@slaapi-none.example");
        UUID caseId = fixture.runAsReturning(t, () -> sla.open(t, new co.ara.onboarding.workflow.WorkflowDefinitionRequest(
                java.util.List.of(SlaTestSupport.slaStage("s1", "S1", java.util.List.of(
                        co.ara.onboarding.workflow.WorkflowFixtures.milestone("m1", "M", 1, java.util.List.of(),
                                java.util.List.of(co.ara.onboarding.workflow.WorkflowFixtures.manual("x")))), null, false)),
                java.util.List.of(), 0L)));
        mvc.perform(as(get(url("slaapi-none", caseId)), admin)).andExpect(status().isNotFound());
    }

    @Test
    void anOutOfScopeCaseIs404NotForbiddenWhileTheirOwnIs200() throws Exception {
        UUID t = fixture.createTenant("slaapi-scope");
        var viewer = new AtomicReference<AppUser>();
        var mine = new AtomicReference<UUID>();
        var theirs = new AtomicReference<UUID>();
        fixture.runAs(t, () -> {
            AppUser v = fixture.createUserWithPassword(t, "v@slaapi-scope.example", "long-enough-password");
            viewer.set(v);
            UUID myTeam = fixture.createTeam(t, "Mine");
            UUID otherTeam = fixture.createTeam(t, "Other");
            fixture.addToTeam(t, v.getId(), myTeam);
            mine.set(caseOfTeam(t, myTeam));
            theirs.set(caseOfTeam(t, otherTeam));
            UUID role = roles.createRole("Sla Team Viewer", "", Map.of(
                    PermissionKeys.CASE_VIEW, Scope.TEAM, PermissionKeys.SLA_VIEW, Scope.TEAM,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
            roles.assignRole(v.getId(), role);
        });
        mvc.perform(as(get(url("slaapi-scope", theirs.get())), viewer.get())).andExpect(status().isNotFound());
        mvc.perform(as(get(url("slaapi-scope", mine.get())), viewer.get())).andExpect(status().isOk());
    }

    /** A case holder without sla.view is refused the clock even though they may read the case. */
    @Test
    void caseViewWithoutSlaViewIsRefused() throws Exception {
        UUID t = fixture.createTenant("slaapi-noperm");
        var viewer = new AtomicReference<AppUser>();
        var caseId = new AtomicReference<UUID>();
        fixture.runAs(t, () -> {
            AppUser v = fixture.createUserWithPassword(t, "v@slaapi-noperm.example", "long-enough-password");
            viewer.set(v);
            caseId.set(sla.caseWithSla(t, 3, true));
            roles.assignRole(v.getId(), roles.createRole("Case Only", "", Map.of(
                    PermissionKeys.CASE_VIEW, Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL)));
        });
        mvc.perform(as(get(url("slaapi-noperm", caseId.get())), viewer.get())).andExpect(status().isForbidden());
    }

    private UUID caseOfTeam(UUID t, UUID team) {
        UUID customer = fixture.createCustomer(t, "Cust " + Uuid7.generate(), null, null, team);
        return sla.openFor(customer);
    }

    @Test
    void anotherTenantsCaseIs404() throws Exception {
        UUID t1 = fixture.createTenant("slaapi-x1");
        UUID t2 = fixture.createTenant("slaapi-x2");
        AppUser admin2 = fixture.createAdminUser(t2, "a@slaapi-x2.example");
        UUID caseId = fixture.runAsReturning(t1, () -> sla.caseWithSla(t1, 3, true));
        mvc.perform(as(get(url("slaapi-x2", caseId)), admin2)).andExpect(status().isNotFound());
    }

    @Test
    void theViewCarriesItsEscalation() throws Exception {
        UUID t = fixture.createTenant("slaapi-esc");
        AppUser admin = fixture.createAdminUser(t, "a@slaapi-esc.example");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 3, true));
        UUID manager = fixture.runAsReturning(t, () -> fixture.createUser(t, "mgr@slaapi-esc.example"));
        UUID clockId = sla.openClockId(caseId);
        String managerName = ownerJdbc().queryForObject("select full_name from app_user where id = ?", String.class, manager);
        Instant now = Instant.now();
        ownerJdbc().update("""
                insert into escalation (id, tenant_id, subject_type, subject_id, case_id, route, escalated_to_user_id,
                    due_date_at_escalation, overdue_days, escalated_at, created_at, updated_at)
                values (?, ?, 'SLA_CLOCK', ?, ?, 'MANAGER', ?, ?, 1, ?, ?, ?)""",
                Uuid7.generate(), t, clockId, caseId, manager, LocalDate.now(),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        mvc.perform(as(get(url("slaapi-esc", caseId)), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escalatedTo.route").value("MANAGER"))
                .andExpect(jsonPath("$.escalatedTo.name").value(managerName));
    }
}
