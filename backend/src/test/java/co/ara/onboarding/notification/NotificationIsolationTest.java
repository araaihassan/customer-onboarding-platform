package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.document.CreateDocumentRequest;
import co.ara.onboarding.document.CreateDocumentRequestRequest;
import co.ara.onboarding.document.DocumentCategory;
import co.ara.onboarding.document.DocumentRequestService;
import co.ara.onboarding.document.DocumentService;
import co.ara.onboarding.document.VisibilityTier;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.scheduling.NotificationSweepJob;
import co.ara.onboarding.security.SecurityTestBase;
import co.ara.onboarding.sla.SlaTestSupport;
import co.ara.onboarding.task.CreateTaskRequest;
import co.ara.onboarding.task.TaskPriority;
import co.ara.onboarding.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-tenant negatives for sub-project 6B (spec 10.1, invariant 10). Tenant B owns notifications, a
 * template, and overdue/expiring work; tenant A's full-authority administrator gets a 404 for every
 * one of B's ids, sees none of B's rows, and a tenant-A sweep never writes a row for tenant B.
 */
class NotificationIsolationTest extends SecurityTestBase {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%abc\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);
    private static final Map<String, Scope> VIEWER = Map.of(
            PermissionKeys.TASK_VIEW, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL,
            PermissionKeys.DOCUMENT_VIEW, Scope.ALL);

    @Autowired SlaTestSupport sla;
    @Autowired TaskService tasks;
    @Autowired DocumentService documents;
    @Autowired DocumentRequestService requests;
    @Autowired BusinessCalendar calendar;
    @Autowired NotificationSweepJob job;
    @Autowired NotificationTestSupport support;
    @Autowired NotificationAdminService templates;

    private record Side(UUID tenant, UUID owner, UUID caseId, UUID taskId, UUID docId, UUID requestId) {}

    private record World(Side a, Side b, AppUser adminA, UUID templateB, UUID templateA) {}

    private void seedConfig(UUID t) {
        ownerJdbc().update("insert into business_calendar (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", t);
        ownerJdbc().update("insert into notification_policy (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", t);
        for (String kind : List.of("TASK_DUE", "MILESTONE_DUE", "DOCUMENT_REQUEST_DUE", "DOCUMENT_EXPIRY")) {
            ownerJdbc().update("delete from deadline_horizon where tenant_id = ? and kind = ?", t, kind);
            for (int d : new int[]{30, 14, 7}) {
                ownerJdbc().update("insert into deadline_horizon (id, tenant_id, kind, lead_days, created_at, updated_at) "
                        + "values (gen_random_uuid(), ?, ?, ?, now(), now())", t, kind, d);
            }
        }
    }

    /** One tenant with an overdue task, a document expiring in five days and an overdue open request. */
    private Side side(UUID t, String slug) {
        seedConfig(t);
        UUID owner = fixture.runAsReturning(t, () -> fixture.createUser(t, "owner@" + slug + ".test"));
        support.grant(t, owner, VIEWER);
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        ownerJdbc().update("update onboarding_case set owner_user_id = ? where id = ?", owner, caseId);
        LocalDate today = fixture.runAsReturning(t, () -> calendar.today());
        UUID taskId = fixture.runAsReturning(t, () -> tasks.create(caseId, new CreateTaskRequest(
                sla.milestoneIdAt(caseId, 0), null, "Chase the pack", null, TaskPriority.MEDIUM, owner, null)).id());
        ownerJdbc().update("update task set due_date = ? where id = ?", today.minusDays(1), taskId);
        UUID uploader = fixture.createAdministrator(t, "uploader+" + Uuid7.generate() + "@example.com");
        UUID[] doc = new UUID[1];
        fixture.runAsUser(t, uploader, () -> doc[0] = documents.upload(caseId,
                new CreateDocumentRequest("Insurance certificate", DocumentCategory.OTHER, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf").id());
        ownerJdbc().update("update document set expires_at = ? where id = ?",
                Timestamp.from(today.plusDays(5).atTime(12, 0).toInstant(ZoneOffset.UTC)), doc[0]);
        UUID request = fixture.runAsReturning(t, () -> requests.create(caseId, new CreateDocumentRequestRequest(
                DocumentCategory.OTHER, "need it", Instant.now().minus(1, ChronoUnit.DAYS), false, null)).id());
        return new Side(t, owner, caseId, taskId, doc[0], request);
    }

    private World world(String prefix) {
        UUID a = fixture.createTenant(prefix + "-a");
        UUID b = fixture.createTenant(prefix + "-b");
        AppUser adminA = fixture.createAdminUser(a, "admin@" + prefix + "-a.example");
        fixture.createAdminUser(b, "admin@" + prefix + "-b.example");
        Side sa = side(a, prefix + "-a");
        Side sb = side(b, prefix + "-b");
        UUID templateB = fixture.runAsReturning(b, () -> templates.createTemplate(
                new CreateTemplateRequest("kickoff", "B kickoff", "{case} entered {stage}", "b body", null, null, true)).id());
        UUID templateA = fixture.runAsReturning(a, () -> templates.createTemplate(
                new CreateTemplateRequest("kickoff", "A kickoff", "{case} entered {stage}", "a body", null, null, true)).id());
        return new World(sa, sb, adminA, templateB, templateA);
    }

    private static String base(String slug) { return "/api/t/" + slug; }

    private static String tpl(String name) {
        return "{\"key\":\"kickoff\",\"name\":\"" + name + "\",\"enteredSubject\":\"s {case}\",\"enteredBody\":\"b\","
                + "\"exitedSubject\":null,\"exitedBody\":null,\"active\":true}";
    }

    private long rowsFor(UUID tenant) {
        return ownerJdbc().queryForObject("select count(*) from notification where tenant_id = ?", Long.class, tenant);
    }

    @Test
    void aTenantASweepNotifiesItsOwnOverdueWorkAndNeverWritesForTenantB() {
        World w = world("ni-sweep");
        // arranging the work already wrote B's "assigned" rows, so the baseline is a snapshot, not zero
        long bBefore = rowsFor(w.b().tenant());
        long bOutboxBefore = ownerJdbc().queryForObject("select count(*) from email_outbox where tenant_id = ?",
                Long.class, w.b().tenant());
        String sweepTypes = "('TASK_OVERDUE','DEADLINE_APPROACHING','EXPIRY_RENEWAL')";
        assertThat(ownerJdbc().queryForObject("select count(*) from notification where tenant_id = ? and type in "
                + sweepTypes, Long.class, w.b().tenant())).isZero();

        job.runOne(w.a().tenant());

        // positive control: A's own overdue task was notified to A's owner
        var types = support.rowsFor(w.a().tenant(), w.a().owner()).stream().map(r -> r.get("type")).toList();
        assertThat(types).contains("TASK_OVERDUE");
        // negative: A's sweep wrote nothing for B -- no new row, no sweep-type row, no outbox row
        assertThat(rowsFor(w.b().tenant())).isEqualTo(bBefore);
        assertThat(ownerJdbc().queryForObject("select count(*) from notification where tenant_id = ? and type in "
                + sweepTypes, Long.class, w.b().tenant())).isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from email_outbox where tenant_id = ?", Long.class,
                w.b().tenant())).isEqualTo(bOutboxBefore);

        // and B's own sweep does produce rows, so the zero above could have been otherwise
        job.runOne(w.b().tenant());
        assertThat(support.rowsFor(w.b().tenant(), w.b().owner()).stream().map(r -> r.get("type")).toList())
                .contains("TASK_OVERDUE");
        assertThat(ownerJdbc().queryForObject("select count(*) from notification where tenant_id = ? "
                + "and subject_id in (?, ?, ?, ?)", Long.class, w.a().tenant(),
                w.b().taskId(), w.b().docId(), w.b().requestId(), w.b().caseId())).isZero();
    }

    @Test
    void anotherTenantsNotificationIsA404ToMarkReadAndStaysUnread() throws Exception {
        World w = world("ni-read");
        job.runOne(w.b().tenant());
        UUID rowB = support.rowsFor(w.b().tenant(), w.b().owner()).stream()
                .map(r -> (UUID) r.get("id")).findFirst().orElseThrow();

        mvc.perform(as(post(base("ni-read-a") + "/notifications/" + rowB + "/read"), w.adminA()))
                .andExpect(status().isNotFound());
        assertThat(ownerJdbc().queryForObject("select read_at from notification where id = ?", Timestamp.class, rowB))
                .isNull();
        // an invented id answers the same as a foreign one
        mvc.perform(as(post(base("ni-read-a") + "/notifications/" + Uuid7.generate() + "/read"), w.adminA()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theInboxListsNoneOfTenantBsRows() throws Exception {
        World w = world("ni-inbox");
        job.runOne(w.a().tenant());
        job.runOne(w.b().tenant());
        assertThat(rowsFor(w.b().tenant())).isPositive();

        mvc.perform(as(get(base("ni-inbox-a") + "/notifications"), w.adminA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + ownerJdbc().queryForObject(
                        "select id from notification where tenant_id = ? limit 1", UUID.class, w.b().tenant()) + "')]",
                        hasSize(0)));
        mvc.perform(as(get(base("ni-inbox-a") + "/notifications/unread-count"), w.adminA()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void anotherTenantsTemplateIsA404ToUpdateAndInvisibleInTheList() throws Exception {
        World w = world("ni-tpl");
        mvc.perform(as(put(base("ni-tpl-a") + "/admin/notification-templates/" + w.templateB())
                        .contentType(MediaType.APPLICATION_JSON).content(tpl("Hijacked")), w.adminA()))
                .andExpect(status().isNotFound());
        assertThat(ownerJdbc().queryForObject("select name from notification_template where id = ?", String.class,
                w.templateB())).isEqualTo("B kickoff");
        // the same call on the tenant's own template succeeds, so the 404 above is about the id
        mvc.perform(as(put(base("ni-tpl-a") + "/admin/notification-templates/" + w.templateA())
                        .contentType(MediaType.APPLICATION_JSON).content(tpl("Renamed")), w.adminA()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed"));
        mvc.perform(as(get(base("ni-tpl-a") + "/admin/notification-templates"), w.adminA()))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(w.templateA().toString()));
    }
}
