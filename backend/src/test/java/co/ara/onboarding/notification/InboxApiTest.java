package co.ara.onboarding.notification;

import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.security.SecurityTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 6B spec 7.3: the caller's own inbox -- list, unread count, mark read. */
class InboxApiTest extends SecurityTestBase {

    private String slug;
    private UUID tenant;
    private AppUser me;
    private AppUser other;
    private UUID customerId;

    @BeforeEach
    void seed() {
        slug = "inbox-" + Uuid7.generate();
        tenant = fixture.createTenant(slug);
        me = fixture.createUserWithPassword(tenant, "me+" + Uuid7.generate() + "@inbox.example", "long-enough-password");
        other = fixture.createUserWithPassword(tenant, "other+" + Uuid7.generate() + "@inbox.example", "long-enough-password");
        // Every row below is about a customer both users may view: the inbox re-checks each row against
        // its recipient's current access (InboxVisibilityTest), and these tests are about paging and state.
        customerId = fixture.runAsReturning(tenant, () -> fixture.createCustomer(tenant, "Acme", null, null, null));
        for (AppUser u : List.of(me, other)) {
            fixture.grantAtAllScope(tenant, u.getId(), co.ara.onboarding.authz.PermissionKeys.CUSTOMER_VIEW);
        }
    }

    private String base() { return "/api/t/" + slug + "/notifications"; }

    private UUID row(UUID tenant, UUID recipient, String title, boolean inApp, boolean read) {
        UUID id = co.ara.onboarding.platform.Uuid7.generate();
        ownerJdbc().update("""
            insert into notification (id, tenant_id, recipient_user_id, type, title, body, link_path, subject_type,
                subject_id, in_app, email_state, tone, read_at, created_at, updated_at)
            values (?, ?, ?, 'TASK_ASSIGNED', ?, 'b', '/t/x', 'customer', ?, ?, 'NONE', 'INFO', ?, now(), now())""",
            id, tenant, recipient, title, customerId, inApp, read ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
        return id;
    }

    private Object readAt(UUID id) {
        return ownerJdbc().queryForObject("select read_at from notification where id = ?", Object.class, id);
    }

    private String json(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    @Test
    void listsMyInAppRowsNewestFirstWithACursor() throws Exception {
        row(tenant, me.getId(), "one", true, false);
        row(tenant, other.getId(), "theirs-1", true, false);
        row(tenant, me.getId(), "two", true, false);
        row(tenant, me.getId(), "hidden", false, false);
        row(tenant, me.getId(), "three", true, true);
        row(tenant, other.getId(), "theirs-2", true, false);
        row(tenant, me.getId(), "four", true, false);
        row(tenant, me.getId(), "five", true, false);

        String page1 = json(mvc.perform(as(get(base()).param("limit", "2"), me))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(page1, "$.items[*].title")).containsExactly("five", "four");
        String cursor1 = JsonPath.read(page1, "$.nextCursor");
        assertThat(cursor1).isNotNull();
        assertThat(JsonPath.<Integer>read(page1, "$.unreadCount")).isEqualTo(4); // one, two, four, five

        String page2 = json(mvc.perform(as(get(base()).param("limit", "2").param("cursor", cursor1), me))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(page2, "$.items[*].title")).containsExactly("three", "two");
        assertThat(JsonPath.<List<Boolean>>read(page2, "$.items[*].read")).containsExactly(true, false);
        String cursor2 = JsonPath.read(page2, "$.nextCursor");
        assertThat(cursor2).isNotNull();

        mvc.perform(as(get(base()).param("limit", "2").param("cursor", cursor2), me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].title").value(org.hamcrest.Matchers.contains("one")))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void unreadCountCountsOnlyMyUnreadInAppRows() throws Exception {
        row(tenant, me.getId(), "a", true, false);
        row(tenant, me.getId(), "b", true, false);
        row(tenant, me.getId(), "read", true, true);
        row(tenant, me.getId(), "email-only", false, false);
        row(tenant, other.getId(), "theirs", true, false);

        mvc.perform(as(get(base() + "/unread-count"), me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(2));
    }

    @Test
    void markReadIsIdempotentAndMine() throws Exception {
        UUID id = row(tenant, me.getId(), "a", true, false);

        mvc.perform(as(post(base() + "/" + id + "/read"), me)).andExpect(status().isNoContent());
        Object first = readAt(id);
        assertThat(first).isNotNull();
        mvc.perform(as(post(base() + "/" + id + "/read"), me)).andExpect(status().isNoContent());
        assertThat(readAt(id)).isEqualTo(first);

        mvc.perform(as(get(base()), me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.items[0].read").value(true))
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void anotherUsersNotificationIs404() throws Exception {
        UUID theirs = row(tenant, other.getId(), "theirs", true, false);

        mvc.perform(as(post(base() + "/" + theirs + "/read"), me)).andExpect(status().isNotFound());
        assertThat(readAt(theirs)).isNull();
    }

    @Test
    void aCrossTenantIdIs404() throws Exception {
        String otherSlug = "inbox-x-" + Uuid7.generate();
        UUID otherTenant = fixture.createTenant(otherSlug);
        AppUser foreigner = fixture.createUserWithPassword(otherTenant, "f+" + Uuid7.generate() + "@inbox.example",
                "long-enough-password");
        UUID foreign = row(otherTenant, foreigner.getId(), "foreign", true, false);

        mvc.perform(as(post(base() + "/" + foreign + "/read"), me)).andExpect(status().isNotFound());
        assertThat(readAt(foreign)).isNull();
    }

    @Test
    void markAllReadClearsTheBadge() throws Exception {
        row(tenant, me.getId(), "a", true, false);
        row(tenant, me.getId(), "b", true, false);
        row(tenant, me.getId(), "already", true, true);
        row(tenant, me.getId(), "email-only", false, false);
        UUID theirs = row(tenant, other.getId(), "theirs", true, false);

        mvc.perform(as(post(base() + "/read-all"), me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marked").value(2));
        mvc.perform(as(get(base() + "/unread-count"), me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
        assertThat(readAt(theirs)).isNull();
    }

    @Test
    void aLimitOutsideOneToHundredIs400() throws Exception {
        mvc.perform(as(get(base()).param("limit", "0"), me)).andExpect(status().isBadRequest());
        mvc.perform(as(get(base()).param("limit", "101"), me)).andExpect(status().isBadRequest());
        mvc.perform(as(get(base()).param("limit", "100"), me)).andExpect(status().isOk());
    }

    @Test
    void aPortalUserSeesAnEmptyInbox() throws Exception {
        AppUser portal = fixture.createPortalUser(tenant, "portal+" + Uuid7.generate() + "@inbox.example");
        row(tenant, me.getId(), "staff-only", true, false);

        mvc.perform(as(get(base()), portal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.unreadCount").value(0));
        mvc.perform(as(get(base() + "/unread-count"), portal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }
}
