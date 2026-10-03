package co.ara.onboarding.sla;

import co.ara.onboarding.document.CreateDocumentRequestRequest;
import co.ara.onboarding.document.DocumentCategory;
import co.ara.onboarding.document.DocumentRequestService;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.security.SecurityTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-tenant negatives for sub-project 6 (spec 10.1). Tenant B owns a case with a clock, an
 * escalation, a holiday and an open document request; tenant A's FULL-authority administrator
 * must get a 404 for every one of B's ids -- RLS plus AuthorizedQuery, not scope, is under test.
 */
class SlaIsolationTest extends SecurityTestBase {

    @Autowired SlaTestSupport sla;
    @Autowired CalendarAdminService calendar;
    @Autowired DocumentRequestService requests;

    private record World(UUID a, UUID b, AppUser adminA, UUID caseA, UUID caseB, UUID holidayB, UUID requestB,
                         UUID userB) {}

    private World world(String prefix) {
        UUID a = fixture.createTenant(prefix + "-a");
        UUID b = fixture.createTenant(prefix + "-b");
        AppUser adminA = fixture.createAdminUser(a, "a@" + prefix + "-a.example");
        UUID caseA = fixture.runAsReturning(a, () -> sla.caseWithSla(a, 3, true));
        UUID caseB = fixture.runAsReturning(b, () -> sla.caseWithSla(b, 3, true));
        UUID userB = fixture.runAsReturning(b, () -> fixture.createUser(b, "u@" + prefix + "-b.example"));
        UUID holidayB = fixture.runAsReturning(b, () ->
                calendar.addHoliday(new CreateHolidayRequest(LocalDate.now().plusDays(30), "B day")).id());
        UUID requestB = fixture.runAsReturning(b, () -> requests.create(caseB,
                new CreateDocumentRequestRequest(DocumentCategory.OTHER, "need it", null, false, null)).id());
        Timestamp ts = Timestamp.from(Instant.now());
        for (UUID[] p : new UUID[][]{{a, caseA}, {b, caseB}}) {
            ownerJdbc().update("""
                    insert into escalation (id, tenant_id, subject_type, subject_id, case_id, route,
                        escalated_to_user_id, due_date_at_escalation, overdue_days, escalated_at, created_at, updated_at)
                    values (?, ?, 'SLA_CLOCK', ?, ?, 'MANAGER', ?, ?, 1, ?, ?, ?)""",
                    Uuid7.generate(), p[0], sla.openClockId(p[1]), p[1], userB, LocalDate.now(), ts, ts, ts);
            ownerJdbc().update("update sla_clock set breached_at = ? where case_id = ? and stopped_at is null",
                    ts, p[1]);
        }
        return new World(a, b, adminA, caseA, caseB, holidayB, requestB, userB);
    }

    private static String base(String slug) { return "/api/t/" + slug; }

    @Test
    void anotherTenantsClockIsA404ButTheOwnIsA200() throws Exception {
        World w = world("sla-iso-clock");
        mvc.perform(as(get(base("sla-iso-clock-a") + "/cases/" + w.caseB() + "/sla-clock"), w.adminA()))
                .andExpect(status().isNotFound());
        mvc.perform(as(get(base("sla-iso-clock-a") + "/cases/" + w.caseA() + "/sla-clock"), w.adminA()))
                .andExpect(status().isOk());
    }

    @Test
    void anotherTenantsHolidayCannotBeRemoved() throws Exception {
        World w = world("sla-iso-hol");
        mvc.perform(as(post(base("sla-iso-hol-a") + "/admin/business-calendar/holidays/" + w.holidayB() + "/remove"),
                w.adminA())).andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(ownerJdbc().queryForObject(
                "select count(*) from business_holiday where id = ?", Integer.class, w.holidayB())).isEqualTo(1);
    }

    @Test
    void anotherTenantsDocumentRequestCannotBeReminded() throws Exception {
        World w = world("sla-iso-rem");
        mvc.perform(as(post(base("sla-iso-rem-a") + "/document-requests/" + w.requestB() + "/remind"), w.adminA()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theExceptionsFeedListsOnlyTheCallersOwnTenant() throws Exception {
        World w = world("sla-iso-feed");
        mvc.perform(as(get(base("sla-iso-feed-a") + "/sla/exceptions"), w.adminA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breached", hasSize(1)))
                .andExpect(jsonPath("$.breached[0].caseId").value(w.caseA().toString()))
                .andExpect(jsonPath("$.summary.breached").value(1))
                .andExpect(content().string(not(containsString(w.caseB().toString()))));
    }

    @Test
    void aManagerOrDepartmentHeadFromAnotherTenantIsA404() throws Exception {
        World w = world("sla-iso-org");
        UUID userA = fixture.runAsReturning(w.a(), () -> fixture.createUser(w.a(), "x@sla-iso-org-a.example"));
        UUID deptA = fixture.runAsReturning(w.a(), () -> fixture.createDepartment(w.a(), "Dept A"));
        mvc.perform(as(put(base("sla-iso-org-a") + "/admin/users/" + userA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"X\",\"departmentId\":null,\"managerId\":\"" + w.userB() + "\"}"),
                w.adminA())).andExpect(status().isNotFound());
        mvc.perform(as(put(base("sla-iso-org-a") + "/admin/departments/" + deptA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dept A\",\"description\":null,\"headUserId\":\"" + w.userB() + "\"}"),
                w.adminA())).andExpect(status().isNotFound());
    }
}
