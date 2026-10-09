package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 6B spec 4.3 / 5.5: tenant notification templates and notification.manage. */
class TemplateAdminTest extends SecurityTestBase {

    @Autowired ObjectMapper json;

    private static JdbcTemplate owner() { return PostgresTestBase.ownerJdbcForSupport(); }

    private static String admin(String slug) { return "/api/t/" + slug + "/admin/notification-templates"; }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder r, String b) {
        return r.contentType(MediaType.APPLICATION_JSON).content(b);
    }

    private AppUser adminOf(String slug) {
        UUID t = fixture.createTenant(slug);
        return fixture.createAdminUser(t, "a@" + slug + ".example");
    }

    private static String tpl(String key, String name, String es, String eb, String xs, String xb, boolean active) {
        return "{\"key\":\"" + key + "\",\"name\":\"" + name + "\",\"enteredSubject\":\"" + es + "\",\"enteredBody\":\"" + eb
                + "\",\"exitedSubject\":" + (xs == null ? "null" : "\"" + xs + "\"")
                + ",\"exitedBody\":" + (xb == null ? "null" : "\"" + xb + "\"") + ",\"active\":" + active + "}";
    }

    private String create(String slug, AppUser a, String key) throws Exception {
        String res = mvc.perform(as(body(post(admin(slug)), tpl(key, "Kickoff", "{case} entered {stage}", "Owner: {owner}", null, null, true)), a))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    @Test
    void anAdministratorCreatesListsAndUpdatesATemplate() throws Exception {
        AppUser a = adminOf("tpl-crud");
        String id = create("tpl-crud", a, "kickoff");
        assertThat(id).isNotBlank();
        mvc.perform(as(get(admin("tpl-crud")), a)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].key").value("kickoff"))
                .andExpect(jsonPath("$[0].exitedSubject").doesNotExist());
        mvc.perform(as(body(put(admin("tpl-crud") + "/" + id),
                        tpl("kickoff", "Kickoff v2", "{case} entered {stage}", "Owner: {owner}", "Left {stage}", "Bye {customer}", true)), a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Kickoff v2"))
                .andExpect(jsonPath("$.exitedBody").value("Bye {customer}"));
        var rows = owner().queryForList("select action, timeline_visible from audit_event where tenant_id = "
                + "(select id from tenant where slug = 'tpl-crud') and action like 'notification_template.%' order by occurred_at");
        assertThat(rows).extracting(r -> r.get("action")).containsExactly("notification_template.created", "notification_template.updated");
        assertThat(rows).allSatisfy(r -> assertThat(r.get("timeline_visible")).isEqualTo(false));
    }

    @Test
    void aDuplicateKeyIs409() throws Exception {
        AppUser a = adminOf("tpl-dup");
        create("tpl-dup", a, "kickoff");
        mvc.perform(as(body(post(admin("tpl-dup")), tpl("kickoff", "Again", "s", "b", null, null, true)), a))
                .andExpect(status().isConflict());
    }

    @Test
    void anUnknownPlaceholderIs422() throws Exception {
        AppUser a = adminOf("tpl-ph");
        mvc.perform(as(body(post(admin("tpl-ph")), tpl("k", "n", "{case} for {contact}", "b", null, null, true)), a))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail", containsString("{contact}")));
    }

    @Test
    void aMalformedKeyIs400() throws Exception {
        AppUser a = adminOf("tpl-key");
        mvc.perform(as(body(post(admin("tpl-key")), tpl("Kick Off", "n", "s", "b", null, null, true)), a))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changingTheKeyIs422() throws Exception {
        AppUser a = adminOf("tpl-chg");
        String id = create("tpl-chg", a, "kickoff");
        mvc.perform(as(body(put(admin("tpl-chg") + "/" + id), tpl("other", "n", "s", "b", null, null, true)), a))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void exitedSubjectWithoutBodyIs422() throws Exception {
        AppUser a = adminOf("tpl-pair");
        mvc.perform(as(body(post(admin("tpl-pair")), tpl("k", "n", "s", "b", "Left", null, true)), a))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(as(body(post(admin("tpl-pair")), tpl("k", "n", "s", "b", null, "Left", true)), a))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void deactivatingRecordsItsOwnAction() throws Exception {
        AppUser a = adminOf("tpl-deact");
        String id = create("tpl-deact", a, "kickoff");
        mvc.perform(as(body(put(admin("tpl-deact") + "/" + id), tpl("kickoff", "Kickoff", "s", "b", null, null, false)), a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        var actions = owner().queryForList("select action from audit_event where tenant_id = "
                + "(select id from tenant where slug = 'tpl-deact') and action like 'notification_template.%' order by occurred_at", String.class);
        assertThat(actions).containsExactly("notification_template.created", "notification_template.deactivated");
    }

    @Test
    void aUserWithoutNotificationManageIs403OnTheAdminRoutes() throws Exception {
        AppUser a = adminOf("tpl-403");
        UUID t = owner().queryForObject("select id from tenant where slug = 'tpl-403'", UUID.class);
        AppUser plain = fixture.createUserWithPassword(t, "plain@tpl-403.example", "long-enough-password");
        String id = create("tpl-403", a, "kickoff");
        mvc.perform(as(get(admin("tpl-403")), plain)).andExpect(status().isForbidden());
        mvc.perform(as(body(post(admin("tpl-403")), tpl("k2", "n", "s", "b", null, null, true)), plain)).andExpect(status().isForbidden());
        mvc.perform(as(body(put(admin("tpl-403") + "/" + id), tpl("kickoff", "n", "s", "b", null, null, true)), plain))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/api/t/tpl-403/notification-templates/options"), plain)).andExpect(status().isForbidden());
    }

    @Test
    void aWorkflowManagerCanReadOptionsButNotTheAdminList() throws Exception {
        AppUser a = adminOf("tpl-wf");
        UUID t = owner().queryForObject("select id from tenant where slug = 'tpl-wf'", UUID.class);
        AppUser wf = fixture.createUserWithPassword(t, "wf@tpl-wf.example", "long-enough-password");
        fixture.grantAtAllScope(t, wf.getId(), PermissionKeys.WORKFLOW_MANAGE);
        create("tpl-wf", a, "kickoff");
        mvc.perform(as(get("/api/t/tpl-wf/notification-templates/options"), wf)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].key").value("kickoff"))
                .andExpect(jsonPath("$[0].name").value("Kickoff"));
        mvc.perform(as(get(admin("tpl-wf")), wf)).andExpect(status().isForbidden());
    }

    @Test
    void optionsListOnlyActiveTemplates() throws Exception {
        AppUser a = adminOf("tpl-opt");
        create("tpl-opt", a, "alpha");
        String beta = create("tpl-opt", a, "beta");
        mvc.perform(as(body(put(admin("tpl-opt") + "/" + beta), tpl("beta", "Kickoff", "s", "b", null, null, false)), a))
                .andExpect(status().isOk());
        String res = mvc.perform(as(get("/api/t/tpl-opt/notification-templates/options"), a)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode n = json.readTree(res);
        assertThat(n).hasSize(1);
        assertThat(n.get(0).get("key").asText()).isEqualTo("alpha");
    }

    @Test
    void templatesAreTenantIsolated() throws Exception {
        AppUser a = adminOf("tpl-iso-a");
        AppUser b = adminOf("tpl-iso-b");
        String id = create("tpl-iso-a", a, "kickoff");
        mvc.perform(as(body(put(admin("tpl-iso-b") + "/" + id), tpl("kickoff", "n", "s", "b", null, null, true)), b))
                .andExpect(status().isNotFound());
        mvc.perform(as(get(admin("tpl-iso-b")), b)).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void requestAndViewAreAligned() {
        Set<String> view = Arrays.stream(TemplateView.class.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
        for (Class<?> c : new Class<?>[]{CreateTemplateRequest.class, UpdateTemplateRequest.class}) {
            Set<String> comps = Arrays.stream(c.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
            assertThat(view).containsAll(comps);
        }
    }

    @Test
    void placeholdersRender() {
        assertThat(TemplatePlaceholders.render("{case} / {owner}", java.util.Map.of("case", "C1", "owner", "Ann"))).isEqualTo("C1 / Ann");
    }
}
