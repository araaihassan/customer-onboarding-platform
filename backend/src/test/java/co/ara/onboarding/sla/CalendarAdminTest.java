package co.ara.onboarding.sla;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.security.SecurityTestBase;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec 8 (admin rows), 4.7: calendar, holiday and SLA policy administration. */
class CalendarAdminTest extends SecurityTestBase {

    @Autowired SlaTestSupport sla;
    @Autowired CaseService cases;
    @Autowired BusinessCalendar calendar;
    @Autowired Clock clock;
    @Autowired ObjectMapper json;

    private static JdbcTemplate owner() { return PostgresTestBase.ownerJdbcForSupport(); }

    private static String base(String slug) { return "/api/t/" + slug + "/admin"; }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder r, String body) {
        return r.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private AppUser admin(String slug) {
        UUID t = fixture.createTenant(slug);
        return fixture.createAdminUser(t, "a@" + slug + ".example");
    }

    @Test
    void anAdministratorReadsTheDefaultCalendar() throws Exception {
        AppUser a = admin("cal-default");
        mvc.perform(as(get(base("cal-default") + "/business-calendar"), a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.workingDays[0]").value(1))
                .andExpect(jsonPath("$.workingDays", hasSize(5)))
                .andExpect(jsonPath("$.holidays", hasSize(0)));
    }

    @Test
    void updatingTheCalendarIsAFullReplace() throws Exception {
        AppUser a = admin("cal-put");
        String body = "{\"name\":\"Berlin office\",\"timezone\":\"Europe/Berlin\",\"workingDays\":[1,2,3,4]}";
        mvc.perform(as(json(put(base("cal-put") + "/business-calendar"), body), a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Berlin office"));
        mvc.perform(as(get(base("cal-put") + "/business-calendar"), a))
                .andExpect(jsonPath("$.name").value("Berlin office"))
                .andExpect(jsonPath("$.timezone").value("Europe/Berlin"))
                .andExpect(jsonPath("$.workingDays", hasSize(4)))
                .andExpect(jsonPath("$.workingDays[3]").value(4));
        // a second PUT replaces, it does not merge
        mvc.perform(as(json(put(base("cal-put") + "/business-calendar"),
                "{\"name\":\"X\",\"timezone\":\"UTC\",\"workingDays\":[6]}"), a)).andExpect(status().isOk());
        mvc.perform(as(get(base("cal-put") + "/business-calendar"), a))
                .andExpect(jsonPath("$.workingDays", hasSize(1)))
                .andExpect(jsonPath("$.workingDays[0]").value(6));
    }

    @Test
    void anUnknownOrNonCanonicalTimezoneIs400() throws Exception {
        AppUser a = admin("cal-tz");
        for (String tz : List.of("Mars/Olympus", "UTC+3", "europe/berlin", " ")) {
            mvc.perform(as(json(put(base("cal-tz") + "/business-calendar"),
                    "{\"name\":\"n\",\"timezone\":\"" + tz + "\",\"workingDays\":[1]}"), a))
                    .andExpect(status().isBadRequest());
        }
        // and the calendar is still readable afterwards
        mvc.perform(as(get(base("cal-tz") + "/business-calendar"), a)).andExpect(status().isOk());
    }

    @Test
    void workingDaysOutsideOneToSevenAre400() throws Exception {
        AppUser a = admin("cal-days");
        for (String days : List.of("[0]", "[8]", "[]", "[1,null]")) {
            mvc.perform(as(json(put(base("cal-days") + "/business-calendar"),
                    "{\"name\":\"n\",\"timezone\":\"UTC\",\"workingDays\":" + days + "}"), a))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void addingAndRemovingAHoliday() throws Exception {
        AppUser a = admin("cal-hol");
        String res = mvc.perform(as(json(post(base("cal-hol") + "/business-calendar/holidays"),
                        "{\"date\":\"2031-12-25\",\"name\":\"Christmas\"}"), a))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(res).get("id").asText();
        mvc.perform(as(get(base("cal-hol") + "/business-calendar"), a))
                .andExpect(jsonPath("$.holidays", hasSize(1)))
                .andExpect(jsonPath("$.holidays[0].date").value("2031-12-25"))
                .andExpect(jsonPath("$.holidays[0].name").value("Christmas"));
        mvc.perform(as(post(base("cal-hol") + "/business-calendar/holidays/" + id + "/remove"), a))
                .andExpect(status().isNoContent());
        mvc.perform(as(get(base("cal-hol") + "/business-calendar"), a))
                .andExpect(jsonPath("$.holidays", hasSize(0)));
    }

    @Test
    void aDuplicateHolidayDateIs409() throws Exception {
        AppUser a = admin("cal-dup");
        String body = "{\"date\":\"2031-01-01\",\"name\":\"New year\"}";
        mvc.perform(as(json(post(base("cal-dup") + "/business-calendar/holidays"), body), a)).andExpect(status().isCreated());
        mvc.perform(as(json(post(base("cal-dup") + "/business-calendar/holidays"), body), a)).andExpect(status().isConflict());
        // the failed insert recorded nothing
        assertThat(owner().queryForObject("select count(*) from audit_event where action = 'calendar.holiday_added' "
                + "and tenant_id = (select id from tenant where slug = 'cal-dup')", Long.class)).isEqualTo(1L);
    }

    @Test
    void aHolidayFromAnotherTenantIs404() throws Exception {
        AppUser a = admin("cal-xa");
        UUID tb = fixture.createTenant("cal-xb");
        UUID foreign = Uuid7.generate();
        owner().update("insert into business_holiday (id, tenant_id, holiday_date, name, created_at, updated_at) "
                + "values (?, ?, date '2031-05-05', 'B only', now(), now())", foreign, tb);
        mvc.perform(as(post(base("cal-xa") + "/business-calendar/holidays/" + foreign + "/remove"), a))
                .andExpect(status().isNotFound());
        assertThat(owner().queryForObject("select count(*) from business_holiday where id = ?", Long.class, foreign)).isEqualTo(1L);
        mvc.perform(as(post(base("cal-xa") + "/business-calendar/holidays/" + Uuid7.generate() + "/remove"), a))
                .andExpect(status().isNotFound());
    }

    @Test
    void aHolidayChangesTheNextDueDateOnly() throws Exception {
        UUID t = fixture.createTenant("cal-due");
        AppUser a = fixture.createAdminUser(t, "a@cal-due.example");
        record Opened(UUID caseId, LocalDate due, LocalDate expectedWithoutHoliday) {}
        var first = fixture.runAsReturning(t, () -> {
            UUID c = sla.caseWithSla(t, 3, true);
            return new Opened(c, cases.roadmap(c).stages().get(0).milestones().get(0).dueDate(),
                    calendar.plusBusinessDays(LocalDate.now(clock), 1));
        });
        assertThat(first.due()).isEqualTo(first.expectedWithoutHoliday());

        mvc.perform(as(json(post(base("cal-due") + "/business-calendar/holidays"),
                "{\"date\":\"" + first.due() + "\",\"name\":\"Surprise day off\"}"), a)).andExpect(status().isCreated());

        var second = fixture.runAsReturning(t, () -> {
            UUID c = sla.caseWithSla(t, 3, true);
            return new Opened(c, cases.roadmap(c).stages().get(0).milestones().get(0).dueDate(),
                    calendar.plusBusinessDays(LocalDate.now(clock), 1));
        });
        assertThat(second.due()).isAfter(first.due());
        assertThat(second.due()).isEqualTo(second.expectedWithoutHoliday());
        // the first case's stored date did not move
        LocalDate stillFirst = fixture.runAsReturning(t,
                () -> cases.roadmap(first.caseId()).stages().get(0).milestones().get(0).dueDate());
        assertThat(stillFirst).isEqualTo(first.due());
    }

    @Test
    void policyRoundTripsAndValidates() throws Exception {
        AppUser a = admin("cal-pol");
        mvc.perform(as(get(base("cal-pol") + "/sla-policy"), a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.atRiskDays").value(1.0))
                .andExpect(jsonPath("$.escalateAfterOverdueDays").value(1));
        mvc.perform(as(json(put(base("cal-pol") + "/sla-policy"),
                "{\"atRiskDays\":2.5,\"escalateAfterOverdueDays\":2}"), a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.atRiskDays").value(2.5));
        mvc.perform(as(get(base("cal-pol") + "/sla-policy"), a))
                .andExpect(jsonPath("$.atRiskDays").value(2.5)).andExpect(jsonPath("$.escalateAfterOverdueDays").value(2));
        for (String bad : List.of("{\"atRiskDays\":-1,\"escalateAfterOverdueDays\":2}",
                "{\"atRiskDays\":1,\"escalateAfterOverdueDays\":0}",
                "{\"atRiskDays\":1}", "{\"escalateAfterOverdueDays\":2}",
                "{\"atRiskDays\":1.25,\"escalateAfterOverdueDays\":2}",
                "{\"atRiskDays\":1000,\"escalateAfterOverdueDays\":2}")) {
            mvc.perform(as(json(put(base("cal-pol") + "/sla-policy"), bad), a)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void withoutCalendarManageEveryRouteIs403() throws Exception {
        UUID t = fixture.createTenant("cal-deny");
        AppUser viewer = fixture.createUserWithPassword(t, "v@cal-deny.example", "long-enough-password");
        fixture.grantAtAllScope(t, viewer.getId(), PermissionKeys.CASE_VIEW);
        fixture.grantAtAllScope(t, viewer.getId(), PermissionKeys.SLA_VIEW);
        String b = base("cal-deny");
        mvc.perform(as(get(b + "/business-calendar"), viewer)).andExpect(status().isForbidden());
        mvc.perform(as(json(put(b + "/business-calendar"),
                "{\"name\":\"n\",\"timezone\":\"UTC\",\"workingDays\":[1]}"), viewer)).andExpect(status().isForbidden());
        mvc.perform(as(json(post(b + "/business-calendar/holidays"),
                "{\"date\":\"2031-01-01\",\"name\":\"n\"}"), viewer)).andExpect(status().isForbidden());
        mvc.perform(as(post(b + "/business-calendar/holidays/" + Uuid7.generate() + "/remove"), viewer)).andExpect(status().isForbidden());
        mvc.perform(as(get(b + "/sla-policy"), viewer)).andExpect(status().isForbidden());
        mvc.perform(as(json(put(b + "/sla-policy"),
                "{\"atRiskDays\":1,\"escalateAfterOverdueDays\":1}"), viewer)).andExpect(status().isForbidden());
    }

    @Test
    void changesAreAuditedOffTheTimelineAndOnlyWhenSomethingChanged() throws Exception {
        AppUser a = admin("cal-aud");
        String b = base("cal-aud");
        String cal = "{\"name\":\"n\",\"timezone\":\"Europe/Paris\",\"workingDays\":[1,2]}";
        mvc.perform(as(json(put(b + "/business-calendar"), cal), a)).andExpect(status().isOk());
        mvc.perform(as(json(put(b + "/business-calendar"), cal), a)).andExpect(status().isOk());   // unchanged
        String res = mvc.perform(as(json(post(b + "/business-calendar/holidays"),
                "{\"date\":\"2031-03-03\",\"name\":\"h\"}"), a)).andReturn().getResponse().getContentAsString();
        JsonNode h = json.readTree(res);
        mvc.perform(as(post(b + "/business-calendar/holidays/" + h.get("id").asText() + "/remove"), a));
        String pol = "{\"atRiskDays\":3,\"escalateAfterOverdueDays\":3}";
        mvc.perform(as(json(put(b + "/sla-policy"), pol), a)).andExpect(status().isOk());
        mvc.perform(as(json(put(b + "/sla-policy"), pol), a)).andExpect(status().isOk());           // unchanged

        var rows = owner().queryForList("select action, timeline_visible from audit_event where tenant_id = "
                + "(select id from tenant where slug = 'cal-aud') and action in ('calendar.updated','calendar.holiday_added',"
                + "'calendar.holiday_removed','sla_policy.updated') order by occurred_at");
        assertThat(rows).extracting(r -> r.get("action")).containsExactly(
                "calendar.updated", "calendar.holiday_added", "calendar.holiday_removed", "sla_policy.updated");
        assertThat(rows).allSatisfy(r -> assertThat(r.get("timeline_visible")).isEqualTo(false));
    }
}
