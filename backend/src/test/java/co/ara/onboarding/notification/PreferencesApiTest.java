package co.ara.onboarding.notification;

import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.security.SecurityTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 6B spec 8: the caller's own per-type channel preferences and email cadence. */
class PreferencesApiTest extends SecurityTestBase {

    private String slug;
    private UUID tenant;
    private AppUser me;
    private AppUser other;

    @BeforeEach
    void seed() {
        slug = "prefs-" + Uuid7.generate();
        tenant = fixture.createTenant(slug);
        me = fixture.createUserWithPassword(tenant, "me+" + Uuid7.generate() + "@prefs.example", "long-enough-password");
        other = fixture.createUserWithPassword(tenant, "other+" + Uuid7.generate() + "@prefs.example", "long-enough-password");
    }

    private String url() { return "/api/t/" + slug + "/notifications/preferences"; }

    private static String typeJson(String type, Boolean inApp, Boolean email) {
        return "{\"type\":\"" + type + "\",\"inApp\":" + inApp + ",\"email\":" + email + "}";
    }

    /** Every opt-out type at its catalogue default, with one override, plus any extra raw entries. */
    private static String body(String cadence, NotificationType override, boolean inApp, boolean email,
                               List<String> extra, Set<NotificationType> omit) {
        List<String> items = new ArrayList<>();
        for (var e : NotificationCatalog.optOut()) {
            if (omit.contains(e.type())) continue;
            boolean a = e.type() == override ? inApp : e.inAppDefault();
            boolean m = e.type() == override ? email : e.emailDefault();
            items.add(typeJson(e.type().name(), a, m));
        }
        items.addAll(extra);
        return "{\"emailCadence\":\"" + cadence + "\",\"types\":[" + String.join(",", items) + "]}";
    }

    private static String body(String cadence, List<String> extra) {
        return body(cadence, null, false, false, extra, Set.of());
    }

    private org.springframework.test.web.servlet.ResultActions doPut(AppUser who, String json) throws Exception {
        return mvc.perform(as(put(url()).contentType(MediaType.APPLICATION_JSON).content(json), who));
    }

    @Test
    void getListsEveryTypeWithDefaultsAndEscalationLocked() throws Exception {
        String json = mvc.perform(as(get(url()), me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.emailCadence").value("IMMEDIATE"))
                .andReturn().getResponse().getContentAsString();

        List<String> types = JsonPath.read(json, "$.types[*].type");
        assertThat(types).hasSize(15)
                .containsExactlyElementsOf(Arrays.stream(NotificationType.values()).map(Enum::name).toList());
        assertThat(JsonPath.<List<Boolean>>read(json, "$.types[?(@.type=='ESCALATION')].locked")).containsExactly(true);
        assertThat(JsonPath.<List<Boolean>>read(json, "$.types[?(@.type=='ESCALATION')].inApp")).containsExactly(true);
        assertThat(JsonPath.<List<Boolean>>read(json, "$.types[?(@.type=='ESCALATION')].email")).containsExactly(true);
        assertThat(JsonPath.<List<Boolean>>read(json, "$.types[?(@.type!='ESCALATION')].locked")).containsOnly(false);
        // A default is the catalogue's own: NEW_CUSTOMER is in-app only.
        assertThat(JsonPath.<List<Boolean>>read(json, "$.types[?(@.type=='NEW_CUSTOMER')].email")).containsExactly(false);
        assertThat(JsonPath.<List<String>>read(json, "$.types[?(@.type=='TASK_ASSIGNED')].label"))
                .containsExactly("Task assigned to me");
    }

    @Test
    void putReplacesEveryTypeAndTheCadence() throws Exception {
        doPut(me, body("DAILY", NotificationType.TASK_ASSIGNED, false, true, List.of(), Set.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailCadence").value("DAILY"));

        String json = mvc.perform(as(get(url()), me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.emailCadence").value("DAILY"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Boolean>>read(json, "$.types[?(@.type=='TASK_ASSIGNED')].inApp")).containsExactly(false);
        assertThat(JsonPath.<List<Boolean>>read(json, "$.types[?(@.type=='TASK_ASSIGNED')].email")).containsExactly(true);

        List<Map<String, Object>> audit = ownerJdbc().queryForList(
                "select timeline_visible from audit_event where tenant_id = ? and action = 'notification.preferences_changed' and resource_id = ?",
                tenant, me.getId());
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("timeline_visible")).isEqualTo(false);

        Integer rows = ownerJdbc().queryForObject(
                "select count(*) from notification_preference where tenant_id = ? and user_id = ?", Integer.class,
                tenant, me.getId());
        assertThat(rows).isEqualTo(14);
    }

    @Test
    void turningEscalationOffIs422() throws Exception {
        doPut(me, body("IMMEDIATE", List.of(typeJson("ESCALATION", false, true))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("required by policy")));
        assertThat(ownerJdbc().queryForObject(
                "select count(*) from notification_preference where tenant_id = ? and user_id = ?", Integer.class,
                tenant, me.getId())).isZero();
    }

    @Test
    void escalationListedOnWithBothTrueIsAccepted() throws Exception {
        doPut(me, body("WEEKLY", List.of(typeJson("ESCALATION", true, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailCadence").value("WEEKLY"))
                .andExpect(jsonPath("$.types[?(@.type=='ESCALATION')].locked").value(org.hamcrest.Matchers.contains(true)));
    }

    @Test
    void omittingATypeIs400NotASilentReset() throws Exception {
        doPut(me, body("DAILY", null, false, false, List.of(), Set.of(NotificationType.NEW_COMMENT)))
                .andExpect(status().isBadRequest());
        mvc.perform(as(get(url()), me)).andExpect(jsonPath("$.emailCadence").value("IMMEDIATE"));
    }

    @Test
    void aDuplicateTypeIs400() throws Exception {
        doPut(me, body("DAILY", List.of(typeJson("TASK_ASSIGNED", false, false))))
                .andExpect(status().isBadRequest());
    }

    /** Final-review minor (c): a null element is a validation failure (400), never an NPE (500). */
    @Test
    void aNullTypeEntryIs400() throws Exception {
        doPut(me, body("DAILY", List.of("null")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(get(url()), me)).andExpect(jsonPath("$.emailCadence").value("IMMEDIATE"));
    }

    @Test
    void aMissingBooleanIs400() throws Exception {
        doPut(me, body("DAILY", null, false, false, List.of(typeJson("TASK_ASSIGNED", null, true)),
                Set.of(NotificationType.TASK_ASSIGNED)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void preferencesAreMineAlone() throws Exception {
        doPut(me, body("DAILY", NotificationType.TASK_ASSIGNED, false, false, List.of(), Set.of()))
                .andExpect(status().isOk());

        String json = mvc.perform(as(get(url()), other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.emailCadence").value("IMMEDIATE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Boolean>>read(json, "$.types[?(@.type=='TASK_ASSIGNED')].inApp")).containsExactly(true);
        assertThat(JsonPath.<List<Boolean>>read(json, "$.types[?(@.type=='TASK_ASSIGNED')].email")).containsExactly(true);
    }

    @Test
    void theRequestAndViewStayAligned() {
        assertThat(names(PreferencesView.class)).containsAll(names(UpdatePreferencesRequest.class));
        assertThat(names(TypePreferenceView.class)).containsAll(names(TypePreferenceRequest.class));
    }

    private static Set<String> names(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
    }
}
