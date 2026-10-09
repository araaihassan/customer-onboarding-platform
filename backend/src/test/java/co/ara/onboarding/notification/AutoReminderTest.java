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
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
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
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
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
    @Autowired CaseService cases;

    @AfterEach void reset() { FlakyEmail.reset(); }

    /** The fixture tenant has no calendar or policy row; seed them, policy on with the given cadence. */
    private NotificationTestSupport.Arranged arranged(String slug, boolean enabled, int interval, int max) {
        var x = support.openRequestWithContact(slug);
        ownerJdbc().update("insert into business_calendar (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", x.tenant());
        ownerJdbc().update("insert into notification_policy (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", x.tenant());
        ownerJdbc().update("update notification_policy set auto_remind_enabled = ?, auto_remind_interval_days = ?, "
                + "auto_remind_max = ? where tenant_id = ?", enabled, interval, max, x.tenant());
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
        var x = arranged("ar-off", false, 3, 3);
        clock.advance(Duration.ofDays(14));
        job.runOne(x.tenant());
        assertThat(reminders(x.tenant())).isEmpty();
        assertThat(remindersSent(x.requestId())).isZero();
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

    @Test
    void theManualButtonsTwentyFourHourFloorIsShared() {
        var x = arranged("ar-floor", true, 3, 3);
        clock.advance(Duration.ofDays(7));
        fixture.runAs(x.tenant(), () -> requests.remind(x.requestId()));
        job.runOne(x.tenant());
        assertThat(reminders(x.tenant())).hasSize(1);          // the manual one only
        assertThat(remindersSent(x.requestId())).isEqualTo(1);
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
            dr.setRequestedAt(java.time.Instant.now());
            ids[2] = requestRepository.saveAndFlush(dr).getId();
        });
        assertThat(ownerJdbc().queryForObject("select count(*) from stage where tenant_id = ? "
                + "and write_scope = 'OWNER_ONLY'", Integer.class, t)).isPositive();
        clock.advance(Duration.ofDays(7));
        job.runOne(t);
        assertThat(reminders(t)).hasSize(1);
        assertThat(remindersSent(ids[2])).isEqualTo(1);
    }

    @Test
    void theSweepCallsNoOtherDocumentRequestServiceMethod() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("co.ara.onboarding");
        DescribedPredicate<JavaCall<?>> other = DescribedPredicate.describe(
                "call a DocumentRequestService method other than remindAutomatically",
                c -> c.getTargetOwner().isAssignableTo(DocumentRequestService.class)
                        && !c.getName().equals("remindAutomatically"));
        noClasses().that().resideInAPackage("..notification..").should().callMethodWhere(other).check(classes);
        // Positive control: the sweep does call remindAutomatically, so the rule is not vacuous.
        DescribedPredicate<JavaCall<?>> sanctioned = DescribedPredicate.describe("remindAutomatically",
                c -> c.getTargetOwner().isAssignableTo(DocumentRequestService.class)
                        && c.getName().equals("remindAutomatically"));
        assertThat(classes.get(NotificationSweepService.class).getMethodCallsFromSelf().stream()
                .anyMatch(sanctioned::test)).isTrue();
    }
}
