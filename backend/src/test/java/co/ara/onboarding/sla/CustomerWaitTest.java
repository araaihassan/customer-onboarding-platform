package co.ara.onboarding.sla;

import co.ara.onboarding.document.Document;
import co.ara.onboarding.document.DocumentCategory;
import co.ara.onboarding.document.DocumentRepository;
import co.ara.onboarding.document.DocumentRequestService;
import co.ara.onboarding.document.CreateDocumentRequestRequest;
import co.ara.onboarding.document.DocumentStatus;
import co.ara.onboarding.document.VisibilityTier;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static co.ara.onboarding.sla.SlaTestSupport.slaStage;
import static co.ara.onboarding.workflow.WorkflowFixtures.document;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.assertj.core.api.Assertions.assertThat;

/** Spec 3.2 / 5 rule 3: an open document request pauses an eligible clock, one interval for any number. */
class CustomerWaitTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired CaseService cases;
    @Autowired DocumentRequestService requests;
    @Autowired DocumentRepository documents;
    @Autowired SlaClockService clockService;

    private UUID newRequest(UUID t, UUID caseId, boolean requiresReview) {
        return fixture.runAsReturning(t, () -> requests.create(caseId,
                new CreateDocumentRequestRequest(DocumentCategory.OTHER, "desc", null, requiresReview, null)).id());
    }

    private UUID newDocument(UUID t, UUID caseId) {
        return fixture.runAsReturning(t, () -> {
            Document d = new Document();
            d.setId(Uuid7.generate());
            d.setTenantId(t);
            d.setCaseId(caseId);
            d.setCustomerId(cases.get(caseId).customerId());
            d.setName("Doc " + Uuid7.generate());
            d.setCategory(DocumentCategory.OTHER);
            d.setVisibilityTier(VisibilityTier.COMPANY_SHARED);
            d.setStatus(DocumentStatus.ACTIVE);
            d.setUploadedBy(fixture.createUser(t, "cw-up+" + Uuid7.generate() + "@example.com"));
            return documents.saveAndFlush(d).getId();
        });
    }

    @Test
    void openingARequestPausesAnEligibleClock() {
        UUID t = fixture.createTenant("cw-open");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 3, true));
        newRequest(t, caseId, false);
        assertThat(sla.openPauseReasons(sla.openClockId(caseId))).containsExactly("OPEN_DOCUMENT_REQUEST");
    }

    @Test
    void anIneligibleStageIsNotPausedByARequest() {
        UUID t = fixture.createTenant("cw-inel");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 3, false));
        newRequest(t, caseId, false);
        UUID clockId = sla.openClockId(caseId);
        assertThat(sla.openPauseReasons(clockId)).isEmpty();
        fixture.runAs(t, () -> cases.hold(caseId, "x"));
        assertThat(sla.openPauseReasons(clockId)).containsExactly("CASE_HOLD");
    }

    @Test
    void twoRequestsShareOneIntervalUntilBothClose() {
        UUID t = fixture.createTenant("cw-two");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 3, true));
        UUID r1 = newRequest(t, caseId, false);
        UUID r2 = newRequest(t, caseId, false);
        UUID clockId = sla.openClockId(caseId);
        UUID doc = newDocument(t, caseId);

        fixture.runAs(t, () -> requests.fulfil(r1, doc));
        assertThat(sla.openPauseReasons(clockId)).containsExactly("OPEN_DOCUMENT_REQUEST");
        fixture.runAs(t, () -> requests.withdraw(r2, "not needed"));
        assertThat(sla.openPauseReasons(clockId)).isEmpty();
        assertThat(sla.closedPauses(clockId)).isEqualTo(1);
    }

    @Test
    void fulfilmentResumesTheClockEvenWhenReviewIsPending() {
        UUID t = fixture.createTenant("cw-review");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 3, true));
        UUID r = newRequest(t, caseId, true);
        UUID clockId = sla.openClockId(caseId);
        UUID doc = newDocument(t, caseId);
        fixture.runAs(t, () -> requests.fulfil(r, doc));
        assertThat(sla.openPauseReasons(clockId)).isEmpty();
        assertThat(sla.closedPauses(clockId)).isEqualTo(1);
    }

    /**
     * Resume must not close the open request pause, and the hold/request overlap is counted once.
     * Starts on a Monday 09:00 UTC; the request opens at once, the hold runs Tuesday to Wednesday
     * 09:00. Paused time at Wednesday 09:00 is the two working days since the request opened; adding
     * the hold's own day on top would read 3.
     */
    @Test
    void resumeWithAnOpenRequestStaysPaused() {
        UUID t = fixture.createTenant("cw-resume");
        java.time.ZonedDateTime now = clock.instant().atZone(java.time.ZoneOffset.UTC);
        java.time.ZonedDateTime monday = now.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
                .withHour(9).withMinute(0).withSecond(0).withNano(0);
        clock.advance(java.time.Duration.between(now, monday));
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID clockId = sla.openClockId(caseId);
        newRequest(t, caseId, false);

        clock.advance(java.time.Duration.ofDays(1));
        fixture.runAs(t, () -> cases.hold(caseId, "x"));
        assertThat(sla.openPauseReasons(clockId)).containsExactly("CASE_HOLD", "OPEN_DOCUMENT_REQUEST");
        clock.advance(java.time.Duration.ofDays(1));
        fixture.runAs(t, () -> cases.resume(caseId));
        assertThat(sla.openPauseReasons(clockId)).containsExactly("OPEN_DOCUMENT_REQUEST");
        assertThat(sla.closedPauses(clockId)).isEqualTo(1);

        SlaClockView view = fixture.runAsReturning(t, () -> clockService.forCase(caseId));
        assertThat(view.state()).isEqualTo(SlaClockState.PAUSED);
        assertThat(view.pauseReason()).isEqualTo(PauseReason.OPEN_DOCUMENT_REQUEST);
        assertThat(view.pausedDays()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(view.elapsedDays()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void aRequestClosingWhileOnHoldLeavesTheHoldPause() {
        UUID t = fixture.createTenant("cw-hold-close");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 3, true));
        UUID clockId = sla.openClockId(caseId);
        UUID r = newRequest(t, caseId, false);
        fixture.runAs(t, () -> cases.hold(caseId, "x"));
        fixture.runAs(t, () -> requests.withdraw(r, "gone"));
        assertThat(sla.openPauseReasons(clockId)).containsExactly("CASE_HOLD");
        fixture.runAs(t, () -> cases.resume(caseId));
        assertThat(sla.openPauseReasons(clockId)).isEmpty();
        assertThat(sla.closedPauses(clockId)).isEqualTo(2);
    }

    @Test
    void anInstantiatedRequestPausesTheFirstClock() {
        UUID t = fixture.createTenant("cw-inst");
        UUID caseId = fixture.runAsReturning(t, () -> sla.open(t, new WorkflowDefinitionRequest(
                List.of(slaStage("s1", "S1", List.of(milestone("m1", "M", 1, List.of(),
                        List.of(document("Upload ID", "OTHER")))), 3, true)), List.of(), 0L)));
        assertThat(sla.openPauseReasons(sla.openClockId(caseId))).containsExactly("OPEN_DOCUMENT_REQUEST");
    }
}
