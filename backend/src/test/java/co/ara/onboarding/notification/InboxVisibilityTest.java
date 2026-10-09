package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.security.SecurityTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Final-review Important 2: the inbox re-checks each row against the recipient's CURRENT access,
 * with the digest's own subject mapping, so a revoked grant or a lost team membership hides the
 * stored text on the very next call -- from the list, from the unread count, and from mark-read.
 */
class InboxVisibilityTest extends SecurityTestBase {

    @Autowired NotificationTestSupport support;

    private String slug;
    private UUID tenant;
    private AppUser me;
    private AppUser other;
    private UUID customerId;

    @BeforeEach
    void seed() {
        slug = "inbox-vis-" + Uuid7.generate();
        tenant = fixture.createTenant(slug);
        me = fixture.createUserWithPassword(tenant, "me+" + Uuid7.generate() + "@inbox.example", "long-enough-password");
        other = fixture.createUserWithPassword(tenant, "other+" + Uuid7.generate() + "@inbox.example", "long-enough-password");
        customerId = fixture.runAsReturning(tenant, () -> fixture.createCustomer(tenant, "Acme", null, null, null));
    }

    private String base() { return "/api/t/" + slug + "/notifications"; }

    private UUID row(UUID recipient, String type, String title, String subjectType, UUID subjectId, UUID caseId) {
        UUID id = Uuid7.generate();
        ownerJdbc().update("""
            insert into notification (id, tenant_id, recipient_user_id, type, title, body, link_path, case_id,
                subject_type, subject_id, in_app, email_state, tone, created_at, updated_at)
            values (?, ?, ?, ?, ?, 'b', '/t/x', ?, ?, ?, true, 'NONE', 'INFO', now(), now())""",
            id, tenant, recipient, type, title, caseId, subjectType, subjectId);
        return id;
    }

    private UUID customerRow(UUID recipient, String title) {
        return row(recipient, "NEW_CUSTOMER", title, "customer", customerId, null);
    }

    private String list(AppUser user, String limit, String cursor) throws Exception {
        var req = get(base()).param("limit", limit);
        if (cursor != null) req = req.param("cursor", cursor);
        return mvc.perform(as(req, user)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private List<String> titles(String page) { return JsonPath.read(page, "$.items[*].title"); }

    private long unreadCount(AppUser user) throws Exception {
        String body = mvc.perform(as(get(base() + "/unread-count"), user)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.unreadCount")).longValue();
    }

    @Test
    void aRevokedGrantHidesTheRowFromTheListTheCountAndMarkRead() throws Exception {
        UUID myRole = support.grant(tenant, me.getId(), Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL));
        support.grant(tenant, other.getId(), Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL));
        UUID mine = customerRow(me.getId(), "Customer assigned to you: Acme");
        customerRow(other.getId(), "Customer assigned to you: Acme (other)");

        assertThat(titles(list(me, "10", null))).containsExactly("Customer assigned to you: Acme");
        assertThat(unreadCount(me)).isEqualTo(1);

        support.revoke(tenant, me.getId(), myRole);

        String page = list(me, "10", null);
        assertThat(titles(page)).isEmpty();
        assertThat(JsonPath.<Integer>read(page, "$.unreadCount")).isZero();
        assertThat(unreadCount(me)).isZero();
        mvc.perform(as(post(base() + "/" + mine + "/read"), me)).andExpect(status().isNotFound());

        // Positive control: the still-privileged recipient keeps reading theirs.
        assertThat(titles(list(other, "10", null))).containsExactly("Customer assigned to you: Acme (other)");
        assertThat(unreadCount(other)).isEqualTo(1);
    }

    /** CLAUDE.md narrowest-scope rule: TEAM scope, lost by leaving the team rather than by losing the grant. */
    @Test
    void leavingTheTeamThatMadeTheSubjectVisibleHidesTheRow() throws Exception {
        UUID team = fixture.runAsReturning(tenant, () -> fixture.createTeam(tenant, "Delivery"));
        fixture.runAs(tenant, () -> fixture.addToTeam(tenant, me.getId(), team));
        ownerJdbc().update("update customer set owning_team_id = ? where id = ?", team, customerId);
        support.grant(tenant, me.getId(), Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
        customerRow(me.getId(), "Customer assigned to you: Acme");

        assertThat(titles(list(me, "10", null))).containsExactly("Customer assigned to you: Acme");
        assertThat(unreadCount(me)).isEqualTo(1);

        ownerJdbc().update("delete from team_member where user_id = ? and team_id = ?", me.getId(), team);

        assertThat(titles(list(me, "10", null))).isEmpty();
        assertThat(unreadCount(me)).isZero();
    }

    @Test
    void aRowWithNothingToCheckAgainstFailsClosedButAnEscalationIsAlwaysShown() throws Exception {
        support.grant(tenant, me.getId(), Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL));
        row(me.getId(), "TASK_ASSIGNED", "about a task that does not exist", "task", Uuid7.generate(), null);
        row(me.getId(), "STAGE_CHANGED", "no subject mapping and no case", "stage", Uuid7.generate(), null);
        // Escalations are delivered without a visibility gate (spec 5.3 step 5) and have no off switch.
        row(me.getId(), "ESCALATION", "escalated to you", "case", Uuid7.generate(), null);
        customerRow(me.getId(), "visible");

        assertThat(titles(list(me, "10", null))).containsExactly("visible", "escalated to you");
        assertThat(unreadCount(me)).isEqualTo(2);
    }

    @Test
    void hiddenRowsDoNotShortenAPageOrStrandItsCursor() throws Exception {
        support.grant(tenant, me.getId(), Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL));
        customerRow(me.getId(), "one");
        customerRow(me.getId(), "two");
        for (int i = 0; i < 5; i++) row(me.getId(), "TASK_ASSIGNED", "gone-" + i, "task", Uuid7.generate(), null);
        customerRow(me.getId(), "three");
        for (int i = 0; i < 5; i++) row(me.getId(), "TASK_ASSIGNED", "gone-late-" + i, "task", Uuid7.generate(), null);

        String page1 = list(me, "2", null);
        assertThat(titles(page1)).containsExactly("three", "two");
        String cursor = JsonPath.read(page1, "$.nextCursor");
        assertThat(cursor).isNotNull();

        String page2 = list(me, "2", cursor);
        assertThat(titles(page2)).containsExactly("one");
        assertThat(page2).doesNotContain("\"nextCursor\":\"");
    }
}
