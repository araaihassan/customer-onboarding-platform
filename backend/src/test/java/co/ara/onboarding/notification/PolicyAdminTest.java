package co.ara.onboarding.notification;

import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.security.SecurityTestBase;
import co.ara.onboarding.support.PostgresTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 6B spec 4.4 / 1.2.1: tenant deadline horizons and the automatic-reminder policy (notification.manage, ALL-only). */
class PolicyAdminTest extends SecurityTestBase {

    @Autowired ObjectMapper json;
    @Autowired co.ara.onboarding.provisioning.TenantProvisioningService provisioning;

    private static JdbcTemplate owner() { return PostgresTestBase.ownerJdbcForSupport(); }

    private static String url(String slug) { return "/api/t/" + slug + "/admin/notification-policy"; }

    private AppUser adminOf(String slug) {
        UUID t = fixture.createTenant(slug);
        return fixture.createAdminUser(t, "a@" + slug + ".example");
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder r, String b) {
        return r.contentType(MediaType.APPLICATION_JSON).content(b);
    }

    private static String policy(Map<String, List<Integer>> horizons, boolean enabled, int interval, int max) {
        String h = horizons.entrySet().stream().map(e -> "\"" + e.getKey() + "\":" + e.getValue())
                .collect(Collectors.joining(","));
        return "{\"autoRemind\":{\"enabled\":" + enabled + ",\"intervalDays\":" + interval + ",\"max\":" + max
                + "},\"horizons\":{" + h + "}}";
    }

    private static Map<String, List<Integer>> allKinds(List<Integer> lead) {
        Map<String, List<Integer>> m = new LinkedHashMap<>();
        for (String k : List.of("TASK_DUE", "MILESTONE_DUE", "DOCUMENT_REQUEST_DUE", "DOCUMENT_EXPIRY",
                "AGREEMENT_EXPIRY", "AGREEMENT_RENEWAL")) m.put(k, lead);
        return m;
    }

    private JsonNode read(String slug, AppUser a) throws Exception {
        String res = mvc.perform(as(get(url(slug)), a)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    private int putStatus(String slug, AppUser a, String payload) throws Exception {
        return mvc.perform(as(body(put(url(slug)), payload), a)).andReturn().getResponse().getStatus();
    }

    @Test
    void aProvisionedTenantIsSeededWithTheDefaultRows() {
        provisioning.provision("pol-seed", "Pol Seed", "admin@pol-seed.example", "Pol Admin");
        String t = "(select id from tenant where slug = 'pol-seed')";
        var pol = owner().queryForList("select auto_remind_enabled, auto_remind_interval_days, auto_remind_max "
                + "from notification_policy where tenant_id = " + t);
        assertThat(pol).hasSize(1);
        assertThat(pol.get(0).get("auto_remind_enabled")).isEqualTo(false);
        assertThat(((Number) pol.get(0).get("auto_remind_interval_days")).intValue()).isEqualTo(3);
        assertThat(((Number) pol.get(0).get("auto_remind_max")).intValue()).isEqualTo(3);
        Map<String, List<Integer>> got = new LinkedHashMap<>();
        owner().queryForList("select kind, lead_days from deadline_horizon where tenant_id = " + t + " order by kind, lead_days")
                .forEach(r -> got.computeIfAbsent((String) r.get("kind"), k -> new java.util.ArrayList<>())
                        .add(((Number) r.get("lead_days")).intValue()));
        assertThat(got).containsOnlyKeys("TASK_DUE", "MILESTONE_DUE", "DOCUMENT_REQUEST_DUE", "DOCUMENT_EXPIRY",
                "AGREEMENT_EXPIRY", "AGREEMENT_RENEWAL");
        for (String k : List.of("TASK_DUE", "MILESTONE_DUE", "DOCUMENT_REQUEST_DUE")) assertThat(got.get(k)).containsExactly(2);
        for (String k : List.of("DOCUMENT_EXPIRY", "AGREEMENT_EXPIRY", "AGREEMENT_RENEWAL")) assertThat(got.get(k)).containsExactly(7, 14, 30);
    }

    @Test
    void aTenantWithNoPolicyRowsReadsTheCodeDefaults() throws Exception {
        // fixture tenants skip provisioning, so this exercises PolicyReader's fallback, not the seed
        AppUser a = adminOf("pol-def");
        JsonNode n = read("pol-def", a);
        for (String k : List.of("TASK_DUE", "MILESTONE_DUE", "DOCUMENT_REQUEST_DUE")) {
            assertThat(n.get("horizons").get(k).toString()).isEqualTo("[2]");
        }
        for (String k : List.of("DOCUMENT_EXPIRY", "AGREEMENT_EXPIRY", "AGREEMENT_RENEWAL")) {
            assertThat(n.get("horizons").get(k).toString()).isEqualTo("[7,14,30]");
        }
        assertThat(n.get("autoRemind").get("enabled").asBoolean()).isFalse();
        assertThat(n.get("autoRemind").get("intervalDays").asInt()).isEqualTo(3);
        assertThat(n.get("autoRemind").get("max").asInt()).isEqualTo(3);
    }

    @Test
    void aSecondPutFullyReplacesTheFirst() throws Exception {
        AppUser a = adminOf("pol-twice");
        Map<String, List<Integer>> h1 = allKinds(List.of(5, 1));
        assertThat(putStatus("pol-twice", a, policy(h1, true, 7, 4))).isEqualTo(200);
        Map<String, List<Integer>> h2 = allKinds(List.of(9));
        h2.put("TASK_DUE", List.of());
        assertThat(putStatus("pol-twice", a, policy(h2, false, 2, 1))).isEqualTo(200);
        JsonNode n = read("pol-twice", a);
        assertThat(n.get("horizons").get("TASK_DUE")).isEmpty();
        assertThat(n.get("horizons").get("MILESTONE_DUE").toString()).isEqualTo("[9]");
        assertThat(n.get("autoRemind").get("enabled").asBoolean()).isFalse();
        assertThat(n.get("autoRemind").get("intervalDays").asInt()).isEqualTo(2);
        assertThat(owner().queryForObject("select count(*) from deadline_horizon where tenant_id = "
                + "(select id from tenant where slug = 'pol-twice')", Integer.class)).isEqualTo(5);
    }

    @Test
    void aNullListForAKindIs400() throws Exception {
        AppUser a = adminOf("pol-nulllist");
        String body = policy(allKinds(List.of(2)), false, 3, 3).replace("\"TASK_DUE\":[2]", "\"TASK_DUE\":null");
        assertThat(putStatus("pol-nulllist", a, body)).isEqualTo(400);
    }

    @Test
    void anUnknownKindIs400() throws Exception {
        AppUser a = adminOf("pol-unk");
        String body = policy(allKinds(List.of(2)), false, 3, 3).replace("\"TASK_DUE\"", "\"BOGUS_DUE\"");
        assertThat(putStatus("pol-unk", a, body)).isEqualTo(400);
    }

    @Test
    void aNullLeadInsideAListIs422() throws Exception {
        AppUser a = adminOf("pol-nullel");
        String body = policy(allKinds(List.of(2)), false, 3, 3).replace("\"TASK_DUE\":[2]", "\"TASK_DUE\":[null]");
        assertThat(putStatus("pol-nullel", a, body)).isEqualTo(422);
    }

    @Test
    void putReplacesEveryKindAndTheReminderPolicy() throws Exception {
        AppUser a = adminOf("pol-put");
        Map<String, List<Integer>> h = allKinds(List.of(5, 1));
        h.put("DOCUMENT_EXPIRY", List.of(60));
        String res = mvc.perform(as(body(put(url("pol-put")), policy(h, true, 7, 4)), a))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(res).get("horizons").get("TASK_DUE").toString()).isEqualTo("[1,5]");
        JsonNode n = read("pol-put", a);
        assertThat(n.get("horizons").get("TASK_DUE").toString()).isEqualTo("[1,5]");
        assertThat(n.get("horizons").get("DOCUMENT_EXPIRY").toString()).isEqualTo("[60]");
        assertThat(n.get("autoRemind").get("enabled").asBoolean()).isTrue();
        assertThat(n.get("autoRemind").get("intervalDays").asInt()).isEqualTo(7);
        assertThat(n.get("autoRemind").get("max").asInt()).isEqualTo(4);
        var rows = owner().queryForList("select action, timeline_visible from audit_event where tenant_id = "
                + "(select id from tenant where slug = 'pol-put') and action = 'notification_policy.updated'");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("timeline_visible")).isEqualTo(false);
    }

    @Test
    void aMissingKindIs400() throws Exception {
        AppUser a = adminOf("pol-miss");
        Map<String, List<Integer>> h = allKinds(List.of(2));
        h.remove("AGREEMENT_RENEWAL");
        assertThat(putStatus("pol-miss", a, policy(h, false, 3, 3))).isEqualTo(400);
    }

    @Test
    void aLeadOutsideOneToNinetyIs422() throws Exception {
        AppUser a = adminOf("pol-range");
        for (int bad : new int[]{0, 91, -3}) {
            Map<String, List<Integer>> h = allKinds(List.of(2));
            h.put("TASK_DUE", List.of(bad));
            assertThat(putStatus("pol-range", a, policy(h, false, 3, 3))).isEqualTo(422);
        }
    }

    @Test
    void moreThanFiveLeadsIs422() throws Exception {
        AppUser a = adminOf("pol-many");
        Map<String, List<Integer>> h = allKinds(List.of(2));
        h.put("TASK_DUE", List.of(1, 2, 3, 4, 5, 6));
        assertThat(putStatus("pol-many", a, policy(h, false, 3, 3))).isEqualTo(422);
    }

    @Test
    void duplicateLeadsAre422() throws Exception {
        AppUser a = adminOf("pol-dupe");
        Map<String, List<Integer>> h = allKinds(List.of(2));
        h.put("TASK_DUE", List.of(3, 3));
        assertThat(putStatus("pol-dupe", a, policy(h, false, 3, 3))).isEqualTo(422);
    }

    @Test
    void anEmptyListTurnsAKindOff() throws Exception {
        AppUser a = adminOf("pol-off");
        Map<String, List<Integer>> h = allKinds(List.of(2));
        h.put("MILESTONE_DUE", List.of());
        assertThat(putStatus("pol-off", a, policy(h, false, 3, 3))).isEqualTo(200);
        assertThat(read("pol-off", a).get("horizons").get("MILESTONE_DUE")).isEmpty();
    }

    @Test
    void anOutOfRangeReminderPolicyIs400() throws Exception {
        AppUser a = adminOf("pol-ar");
        assertThat(putStatus("pol-ar", a, policy(allKinds(List.of(2)), true, 31, 3))).isEqualTo(400);
        assertThat(putStatus("pol-ar", a, policy(allKinds(List.of(2)), true, 3, 11))).isEqualTo(400);
    }

    @Test
    void onlyNotificationManageMayReadOrWrite() throws Exception {
        adminOf("pol-403");
        UUID t = owner().queryForObject("select id from tenant where slug = 'pol-403'", UUID.class);
        AppUser plain = fixture.createUserWithPassword(t, "plain@pol-403.example", "long-enough-password");
        for (String k : List.of(co.ara.onboarding.authz.PermissionKeys.WORKFLOW_MANAGE, co.ara.onboarding.authz.PermissionKeys.USER_MANAGE,
                co.ara.onboarding.authz.PermissionKeys.ROLE_MANAGE, co.ara.onboarding.authz.PermissionKeys.CALENDAR_MANAGE)) {
            fixture.grantAtAllScope(t, plain.getId(), k);
        }
        mvc.perform(as(get(url("pol-403")), plain)).andExpect(status().isForbidden());
        assertThat(putStatus("pol-403", plain, policy(allKinds(List.of(2)), false, 3, 3))).isEqualTo(403);
    }

    @Test
    void policyIsTenantIsolated() throws Exception {
        AppUser a = adminOf("pol-iso-a");
        AppUser b = adminOf("pol-iso-b");
        assertThat(putStatus("pol-iso-b", b, policy(allKinds(List.of(9)), true, 10, 5))).isEqualTo(200);
        JsonNode n = read("pol-iso-a", a);
        assertThat(n.get("horizons").get("TASK_DUE").toString()).isEqualTo("[2]");
        assertThat(n.get("autoRemind").get("enabled").asBoolean()).isFalse();
    }

    @Test
    void requestAndViewAreAligned() {
        Set<String> view = Arrays.stream(PolicyView.class.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
        Set<String> req = Arrays.stream(UpdatePolicyRequest.class.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
        assertThat(view).isEqualTo(req);
        Set<String> ar = Arrays.stream(AutoRemindView.class.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
        assertThat(ar).containsExactlyInAnyOrder("enabled", "intervalDays", "max");
    }
}
