package co.ara.onboarding.notification;

import co.ara.onboarding.document.DocumentCategory;
import co.ara.onboarding.document.DocumentRequest;
import co.ara.onboarding.document.DocumentRequestRepository;
import co.ara.onboarding.document.DocumentRequestService;
import co.ara.onboarding.document.DocumentRequestStatus;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WriteScope;
import co.ara.onboarding.scheduling.NotificationSweepJob;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.assertj.core.api.Assertions.assertThat;

/** 6B spec 6.2: automatic customer reminders on the tenant's cadence (default off). */
@Import(FlakyEmail.class)
class AutoReminderTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired NotificationTestSupport support;
    @Autowired NotificationSweepJob job;
    @Autowired DocumentRequestService requests;
    @Autowired DocumentRequestRepository requestRepository;
    @Autowired JourneyFixtures journey;
    @Autowired co.ara.onboarding.document.DocumentService documents;
    @Autowired CaseService cases;

    @AfterEach void reset() { FlakyEmail.reset(); }

    /** The fixture tenant has no calendar or policy row; seed them, policy on with the given cadence. */
    private NotificationTestSupport.Arranged arranged(String slug, boolean enabled, int interval, int max) {
        var x = support.openRequestWithContact(slug);
        seed(x, enabled, interval, max);
        return x;
    }

    private void seed(NotificationTestSupport.Arranged x, boolean enabled, int interval, int max) {
        ownerJdbc().update("insert into business_calendar (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", x.tenant());
        ownerJdbc().update("insert into notification_policy (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", x.tenant());
        ownerJdbc().update("update notification_policy set auto_remind_enabled = ?, auto_remind_interval_days = ?, "
                + "auto_remind_max = ? where tenant_id = ?", enabled, interval, max, x.tenant());
    }

    /** The real defaults: a policy row exists but nothing is updated, or no policy row at all. */
    private NotificationTestSupport.Arranged defaults(String slug, boolean policyRow) {
        var x = support.openRequestWithContact(slug);
        ownerJdbc().update("insert into business_calendar (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", x.tenant());
        if (policyRow) {
            ownerJdbc().update("insert into notification_policy (id, tenant_id, created_at, updated_at) "
                    + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", x.tenant());
        }
        return x;
    }

    private int remindersSent(UUID requestId) {
        return ownerJdbc().queryForObject("select reminders_sent from document_request where id = ?",
                Integer.class, requestId);
    }

    private List<Map<String, Object>> reminders(UUID tenant) {
        return support.outbox(tenant).stream().filter(r -> "CUSTOMER_REMINDER".equals(r.get("kind"))).toList();
    }

    @Test
    void offByDefaultNothingIsSent() {
        var withRow = defaults("ar-off", true);
        var noRow = defaults("ar-off-norow", false);
        clock.advance(Duration.ofDays(14));
        job.runOne(withRow.tenant());
        job.runOne(noRow.tenant());
        assertThat(ownerJdbc().queryForObject("select auto_remind_enabled from notification_policy "
                + "where tenant_id = ?", Boolean.class, withRow.tenant())).isFalse();
        assertThat(reminders(withRow.tenant())).isEmpty();
        assertThat(reminders(noRow.tenant())).isEmpty();
        assertThat(remindersSent(withRow.requestId())).isZero();
        assertThat(remindersSent(noRow.requestId())).isZero();
        // Positive control: turning the policy on makes the same fixture send.
        ownerJdbc().update("update notification_policy set auto_remind_enabled = true where tenant_id = ?", withRow.tenant());
        job.runOne(withRow.tenant());
        assertThat(reminders(withRow.tenant())).hasSize(1);
    }

    @Test
    void afterTheIntervalTheContactIsRemindedAutomatically() {
        var x = arranged("ar-after", true, 3, 3);
        clock.advance(Duration.ofDays(7));
        job.runOne(x.tenant());
        var rows = reminders(x.tenant());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo("SENT");
        assertThat(FlakyEmail.recipients()).containsExactly(x.contactEmail());
        assertThat(remindersSent(x.requestId())).isEqualTo(1);
        var audit = ownerJdbc().queryForList("select * from audit_event where tenant_id = ? "
                + "and action = 'document_request.reminded'", x.tenant());
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("timeline_visible")).isEqualTo(true);
        assertThat(audit.get(0).get("summary").toString()).contains("(automatic)");
        assertThat(audit.get(0).get("payload").toString()).contains("\"automatic\": true");
    }

    @Test
    void beforeTheIntervalNothingIsSent() {
        var x = arranged("ar-before", true, 3, 3);
        clock.advance(Duration.ofDays(1));
        job.runOne(x.tenant());
        assertThat(reminders(x.tenant())).isEmpty();
        assertThat(remindersSent(x.requestId())).isZero();
    }

    @Test
    void itStopsAtTheMaximum() {
        var x = arranged("ar-max", true, 3, 2);
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofDays(7));
            job.runOne(x.tenant());
        }
        assertThat(reminders(x.tenant())).hasSize(2);
        assertThat(remindersSent(x.requestId())).isEqualTo(2);
    }

    /** Runs remindAutomatically directly as the system principal, bypassing the sweep's interval check. */
    private boolean remindAsSystem(UUID tenant, UUID requestId) {
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(
                        new org.springframework.mock.web.MockHttpServletRequest()));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                co.ara.onboarding.authz.SystemPrincipal.authentication(tenant));
        try {
            boolean[] r = new boolean[1];
            fixture.runUnauthenticated(tenant, () -> r[0] = requests.remindAutomatically(requestId));
            return r[0];
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void theManualButtonsTwentyFourHourFloorIsShared() {
        var x = arranged("ar-floor", true, 3, 3);
        clock.advance(Duration.ofDays(7));
        fixture.runAs(x.tenant(), () -> requests.remind(x.requestId()));
        job.runOne(x.tenant());
        assertThat(reminders(x.tenant())).hasSize(1);          // the manual one only
        // The sweep's interval check skips the row first, so call the service directly: only the floor refuses.
        assertThat(remindAsSystem(x.tenant(), x.requestId())).isFalse();
        assertThat(remindersSent(x.requestId())).isEqualTo(1);
        assertThat(reminders(x.tenant())).hasSize(1);
        // Positive control: past the 24 hours the same direct call sends.
        clock.advance(Duration.ofHours(25));
        assertThat(remindAsSystem(x.tenant(), x.requestId())).isTrue();
        assertThat(remindersSent(x.requestId())).isEqualTo(2);
    }

    @Test
    void aHumanCallerIsRefusedEvenHoldingDocumentRequestAtAll() {
        var x = arranged("ar-human", true, 3, 3);
        UUID admin = fixture.createAdministrator(x.tenant(), "admin+" + Uuid7.generate() + "@ar.test");
        boolean[] r = new boolean[1];
        fixture.runAsUser(x.tenant(), admin, () -> r[0] = requests.remindAutomatically(x.requestId()));
        assertThat(r[0]).isFalse();
        assertThat(remindersSent(x.requestId())).isZero();
        assertThat(reminders(x.tenant())).isEmpty();
        assertThat(remindAsSystem(x.tenant(), x.requestId())).isTrue();   // positive control
    }

    @Test
    void aFulfilledRequestOrACompletedCaseIsNotReminded() {
        var fulfilled = arranged("ar-fulfilled", true, 3, 3);
        UUID admin = fixture.createAdministrator(fulfilled.tenant(), "up+" + Uuid7.generate() + "@ar.test");
        byte[] pdf = "%PDF-1.4\n%abc\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        UUID[] doc = new UUID[1];
        fixture.runAsUser(fulfilled.tenant(), admin, () -> doc[0] = documents.upload(fulfilled.caseId(),
                new co.ara.onboarding.document.CreateDocumentRequest("Done", DocumentCategory.OTHER,
                        co.ara.onboarding.document.VisibilityTier.COMPANY_SHARED, null, null, null, null),
                new java.io.ByteArrayInputStream(pdf), pdf.length, "application/pdf").id());
        ownerJdbc().update("update document_request set status = 'FULFILLED', fulfilled_document_id = ? where id = ?",
                doc[0], fulfilled.requestId());
        var completed = arranged("ar-completed", true, 3, 3);
        ownerJdbc().update("update onboarding_case set status = 'COMPLETED' where id = ?", completed.caseId());
        var active = arranged("ar-active", true, 3, 3);
        clock.advance(Duration.ofDays(7));
        job.runOne(fulfilled.tenant());
        job.runOne(completed.tenant());
        job.runOne(active.tenant());
        assertThat(reminders(fulfilled.tenant())).isEmpty();
        assertThat(reminders(completed.tenant())).isEmpty();
        assertThat(reminders(active.tenant())).hasSize(1);
    }

    @Test
    void aClosedRequestOrRetiredContactIsNeverReminded() {
        var closed = arranged("ar-closed", true, 3, 3);
        ownerJdbc().update("update document_request set status = 'WITHDRAWN' where id = ?", closed.requestId());
        var retired = arranged("ar-retired", true, 3, 3);
        ownerJdbc().update("update customer_contact set status = 'INACTIVE' where id = ?", retired.contactId());
        clock.advance(Duration.ofDays(7));
        job.runOne(closed.tenant());
        job.runOne(retired.tenant());
        assertThat(reminders(closed.tenant())).isEmpty();
        assertThat(reminders(retired.tenant())).isEmpty();
        // Positive control: the same cadence does remind when nothing is closed.
        var open = arranged("ar-open", true, 3, 3);
        clock.advance(Duration.ofDays(7));
        job.runOne(open.tenant());
        assertThat(reminders(open.tenant())).hasSize(1);
    }

    @Test
    void aHeldCaseIsNotReminded() {
        var x = arranged("ar-held", true, 3, 3);
        ownerJdbc().update("update onboarding_case set status = 'ON_HOLD', held_at = now() where id = ?", x.caseId());
        clock.advance(Duration.ofDays(7));
        job.runOne(x.tenant());
        assertThat(reminders(x.tenant())).isEmpty();
    }

    @Test
    void aStageWriteScopeDoesNotBlockTheAutomaticReminder() {
        UUID t = fixture.createTenant("ar-scope");
        ownerJdbc().update("insert into business_calendar (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", t);
        ownerJdbc().update("insert into notification_policy (id, tenant_id, created_at, updated_at, "
                + "auto_remind_enabled) values (gen_random_uuid(), ?, now(), now(), true) "
                + "on conflict (tenant_id) do update set auto_remind_enabled = true", t);
        UUID admin = fixture.createAdministrator(t, "admin+" + Uuid7.generate() + "@ar.test");
        UUID[] ids = new UUID[3];
        fixture.runAsUser(t, admin, () -> {
            UUID owner = fixture.createUser(t, "owner+" + Uuid7.generate() + "@ar.test");
            var stage = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Restricted Stage", null, false, true, true, null,
                    WriteScope.OWNER_ONLY, null, null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))), List.of());
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(stage), List.of(), 0L));
            UUID cust = fixture.createCustomer(t, "AR Co " + Uuid7.generate(), owner, null, null);
            ids[0] = cases.create(new CreateCaseRequest(cust, journey.templateOf(versionId),
                    "AR Case " + Uuid7.generate(), Map.of())).id();
            ids[1] = fixture.createContact(t, cust, "ws+" + Uuid7.generate() + "@customer.example");
            DocumentRequest dr = new DocumentRequest();
            dr.setId(Uuid7.generate());
            dr.setTenantId(t);
            dr.setCaseId(ids[0]);
            dr.setRequestedOfContactId(ids[1]);
            dr.setCategory(DocumentCategory.OTHER);
            dr.setStatus(DocumentRequestStatus.OPEN);
            dr.setRequestedBy(admin);
            dr.setRequestedAt(java.time.Instant.now(clock));
            ids[2] = requestRepository.saveAndFlush(dr).getId();
        });
        assertThat(ownerJdbc().queryForObject("select count(*) from stage where tenant_id = ? "
                + "and write_scope = 'OWNER_ONLY'", Integer.class, t)).isPositive();
        clock.advance(Duration.ofDays(7));
        job.runOne(t);
        assertThat(reminders(t)).hasSize(1);
        assertThat(remindersSent(ids[2])).isEqualTo(1);
    }

    /** The containment rule lives in ModuleBoundaryTest; this proves it is not vacuous. */
    @Test
    void theSweepDoesCallRemindAutomatically() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("co.ara.onboarding");
        assertThat(classes.get(NotificationSweepService.class).getMethodCallsFromSelf().stream()
                .anyMatch(c -> c.getTargetOwner().isAssignableTo(DocumentRequestService.class)
                        && c.getName().equals("remindAutomatically"))).isTrue();
    }
}
