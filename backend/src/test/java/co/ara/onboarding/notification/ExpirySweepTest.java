package co.ara.onboarding.notification;

import co.ara.onboarding.agreement.AgreementRepository;
import co.ara.onboarding.agreement.AgreementTestSupport;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.ParticipantStatus;
import co.ara.onboarding.document.CreateDocumentRequest;
import co.ara.onboarding.document.DocumentCategory;
import co.ara.onboarding.document.DocumentService;
import co.ara.onboarding.document.VisibilityTier;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.scheduling.NotificationSweepJob;
import co.ara.onboarding.sla.SlaTestSupport;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.AgreementRecordMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6B spec 6.2 / PRD section 9: document expiry and agreement expiry and renewal reminders on tenant
 * horizons, in calendar days, every recipient still gated by the subject's own view permission.
 */
class ExpirySweepTest extends PostgresTestBase {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%abc\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired JourneyFixtures journey;
    @Autowired DocumentService documents;
    @Autowired BusinessCalendar calendar;
    @Autowired NotificationSweepJob job;
    @Autowired NotificationTestSupport support;
    @Autowired AgreementTestSupport agreementSupport;
    @Autowired AgreementRepository agreementRepository;

    private static final Map<String, Scope> DOC_VIEWER = Map.of(
            PermissionKeys.DOCUMENT_VIEW, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL);
    private static final Map<String, Scope> AGR_VIEWER = Map.of(
            PermissionKeys.AGREEMENT_VIEW, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL);

    private UUID tenant(String slug) {
        UUID t = fixture.createTenant(slug);
        ownerJdbc().update("insert into business_calendar (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", t);
        ownerJdbc().update("insert into notification_policy (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", t);
        for (String kind : List.of("DOCUMENT_EXPIRY", "AGREEMENT_EXPIRY", "AGREEMENT_RENEWAL")) {
            ownerJdbc().update("delete from deadline_horizon where tenant_id = ? and kind = ?", t, kind);
            for (int d : new int[]{30, 14, 7}) {
                ownerJdbc().update("insert into deadline_horizon (id, tenant_id, kind, lead_days, created_at, updated_at) "
                        + "values (gen_random_uuid(), ?, ?, ?, now(), now())", t, kind, d);
            }
        }
        return t;
    }

    private LocalDate today(UUID t) { return fixture.runAsReturning(t, () -> calendar.today()); }

    private UUID user(UUID t, String email, Map<String, Scope> grants) {
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, email));
        support.grant(t, u, grants);
        return u;
    }

    private UUID caseOwnedBy(UUID t, UUID owner) {
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        ownerJdbc().update("update onboarding_case set owner_user_id = ? where id = ?", owner, caseId);
        return caseId;
    }

    private UUID document(UUID t, UUID caseId, String name, UUID targetDepartment, LocalDate expires) {
        UUID admin = fixture.createAdministrator(t, "uploader+" + Uuid7.generate() + "@example.com");
        UUID[] id = new UUID[1];
        fixture.runAsUser(t, admin, () -> id[0] = documents.upload(caseId,
                new CreateDocumentRequest(name, DocumentCategory.OTHER, VisibilityTier.COMPANY_SHARED,
                        targetDepartment, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf").id());
        setExpiry(id[0], expires);
        return id[0];
    }

    private void setExpiry(UUID docId, LocalDate expires) {
        ownerJdbc().update("update document set expires_at = ? where id = ?",
                Timestamp.from(expires.atTime(12, 0).toInstant(ZoneOffset.UTC)), docId);
    }

    private List<Map<String, Object>> expiryRows(UUID t, UUID recipient) {
        return support.rowsFor(t, recipient).stream().filter(r -> "EXPIRY_RENEWAL".equals(r.get("type"))).toList();
    }

    private List<Map<String, Object>> visible(UUID t, UUID recipient) {
        return expiryRows(t, recipient).stream().filter(r -> Boolean.TRUE.equals(r.get("in_app"))).toList();
    }

    private record Agr(UUID tenant, UUID caseId, UUID agreementId, UUID owner, UUID caseOwner) {}

    private Agr agreement(String slug, Map<String, Scope> ownerGrants, Map<String, Scope> caseOwnerGrants) {
        UUID t = tenant(slug);
        UUID owner = user(t, "aowner@" + slug + ".test", ownerGrants);
        UUID caseOwner = user(t, "cowner@" + slug + ".test", caseOwnerGrants);
        UUID caseId = fixture.runAsReturning(t, () ->
                agreementSupport.openCaseWithSignatureRequirement(t, AgreementRecordMode.STRUCTURED_ONLY));
        UUID agreementId = fixture.runAsReturning(t, () -> agreementRepository.findByCaseId(caseId).get(0).getId());
        ownerJdbc().update("update agreement set owner_user_id = ? where id = ?", owner, agreementId);
        ownerJdbc().update("update onboarding_case set owner_user_id = ? where id = ?", caseOwner, caseId);
        return new Agr(t, caseId, agreementId, owner, caseOwner);
    }

    // ---- documents -----------------------------------------------------------------------

    @Test
    void aDocumentExpiringWithinThirtyDaysRemindsTheCaseOwnerOnceAtThirty() {
        UUID t = tenant("ex-doc");
        UUID owner = user(t, "owner@ex-doc.test", DOC_VIEWER);
        UUID caseId = caseOwnedBy(t, owner);
        UUID doc = document(t, caseId, "Insurance certificate", null, today(t).plusDays(25));

        job.runOne(t);
        var first = visible(t, owner);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).get("subject_type")).isEqualTo("document");
        assertThat(first.get(0).get("subject_id")).isEqualTo(doc);
        assertThat(first.get(0).get("tone")).isEqualTo("WARN");
        assertThat(first.get(0).get("title").toString()).contains("Insurance certificate").contains("25 day(s)");
        assertThat(first.get(0).get("dedupe_key").toString()).startsWith("EXPIRY:DOCUMENT_EXPIRY:" + doc + ":").endsWith(":30");
        job.runOne(t);
        assertThat(expiryRows(t, owner)).hasSize(1);

        clock.advance(Duration.ofDays(12));
        job.runOne(t);
        assertThat(visible(t, owner)).hasSize(2);
        assertThat(visible(t, owner).get(1).get("dedupe_key").toString()).endsWith(":14");

        clock.advance(Duration.ofDays(8));
        job.runOne(t);
        assertThat(visible(t, owner)).hasSize(3);
        assertThat(visible(t, owner).get(2).get("dedupe_key").toString()).endsWith(":7");
    }

    @Test
    void aDocumentFarFromExpiryIsNotReminded() {
        UUID t = tenant("ex-doc-far");
        UUID owner = user(t, "owner@ex-doc-far.test", DOC_VIEWER);
        document(t, caseOwnedBy(t, owner), "Far", null, today(t).plusDays(90));
        job.runOne(t);
        assertThat(expiryRows(t, owner)).isEmpty();
    }

    @Test
    void aRetiredDocumentIsNeverReminded() {
        UUID t = tenant("ex-doc-retired");
        UUID owner = user(t, "owner@ex-doc-retired.test", DOC_VIEWER);
        UUID doc = document(t, caseOwnedBy(t, owner), "Old policy", null, today(t).plusDays(5));
        ownerJdbc().update("update document set status = 'RETIRED' where id = ?", doc);
        job.runOne(t);
        assertThat(expiryRows(t, owner)).isEmpty();
    }

    @Test
    void aHeldCaseIsNotRemindedAbout() {
        UUID t = tenant("ex-doc-held");
        UUID owner = user(t, "owner@ex-doc-held.test", DOC_VIEWER);
        UUID caseId = caseOwnedBy(t, owner);
        document(t, caseId, "Held doc", null, today(t).plusDays(5));
        ownerJdbc().update("update onboarding_case set status = 'ON_HOLD', held_at = now() where id = ?", caseId);
        job.runOne(t);
        assertThat(expiryRows(t, owner)).isEmpty();
    }

    @Test
    void movingTheExpiryDateReArmsTheReminder() {
        UUID t = tenant("ex-doc-rearm");
        UUID owner = user(t, "owner@ex-doc-rearm.test", DOC_VIEWER);
        UUID doc = document(t, caseOwnedBy(t, owner), "Moves", null, today(t).plusDays(25));
        job.runOne(t);
        setExpiry(doc, today(t).plusDays(20));
        job.runOne(t);
        assertThat(visible(t, owner)).hasSize(2);
    }

    @Test
    void aTargetedDocumentsExpiryDoesNotReachAnOwnerOutsideItsAudience() {
        UUID t = tenant("ex-doc-targeted");
        UUID[] depts = new UUID[2];
        fixture.runAs(t, () -> {
            depts[0] = fixture.createDepartment(t, "Sales");
            depts[1] = fixture.createDepartment(t, "Legal");
        });
        UUID outsider = fixture.runAsReturning(t, () -> fixture.createUserInDepartment(t, "sales@ex-doc-t.test", depts[0]));
        support.grant(t, outsider, DOC_VIEWER);
        UUID insider = fixture.runAsReturning(t, () -> fixture.createUserInDepartment(t, "legal@ex-doc-t.test", depts[1]));
        support.grant(t, insider, DOC_VIEWER);
        LocalDate soon = today(t).plusDays(5);
        document(t, caseOwnedBy(t, outsider), "Legal only A", depts[1], soon);
        document(t, caseOwnedBy(t, insider), "Legal only B", depts[1], soon);

        job.runOne(t);

        assertThat(expiryRows(t, outsider)).isEmpty();
        assertThat(visible(t, insider)).hasSize(1);
    }

    @Test
    void aDocumentOwnerWithNarrowViewScopeGetsNothingButAMatchingOneDoes() {
        UUID t = tenant("ex-doc-scope");
        UUID team = user(t, "team@ex-doc-scope.test", Map.of(PermissionKeys.DOCUMENT_VIEW, Scope.TEAM));
        UUID own = user(t, "own@ex-doc-scope.test", Map.of(PermissionKeys.DOCUMENT_VIEW, Scope.ASSIGNED));
        LocalDate soon = today(t).plusDays(5);
        UUID docTeam = document(t, caseOwnedBy(t, team), "Team doc", null, soon);
        UUID docOwn = document(t, caseOwnedBy(t, own), "Own doc", null, soon);
        ownerJdbc().update("update document set uploaded_by = ? where id = ?", own, docOwn);
        job.runOne(t);
        assertThat(docTeam).isNotNull();
        assertThat(expiryRows(t, team)).isEmpty();
        assertThat(visible(t, own)).hasSize(1);
    }

    // ---- agreements ----------------------------------------------------------------------

    @Test
    void anAgreementExpiryRemindsTheAgreementAndCaseOwners() {
        var a = agreement("ex-agr", AGR_VIEWER, AGR_VIEWER);
        ownerJdbc().update("update agreement set expires_at = ? where id = ?", today(a.tenant()).plusDays(10), a.agreementId());
        job.runOne(a.tenant());
        for (UUID u : List.of(a.owner(), a.caseOwner())) {
            var rows = visible(a.tenant(), u);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("subject_type")).isEqualTo("agreement");
            assertThat(rows.get(0).get("subject_id")).isEqualTo(a.agreementId());
            assertThat(rows.get(0).get("title").toString()).contains("expires in 10 day(s)");
            assertThat(rows.get(0).get("dedupe_key").toString()).contains("EXPIRY:AGREEMENT_EXPIRY:").endsWith(":14");
        }
        job.runOne(a.tenant());
        assertThat(expiryRows(a.tenant(), a.owner())).hasSize(2);   // the sent lead-14 row and the consumed lead-30 marker
        assertThat(visible(a.tenant(), a.owner())).hasSize(1);
    }

    @Test
    void renewalCountsBackFromTheNoticeDeadline() {
        var a = agreement("ex-renewal", AGR_VIEWER, AGR_VIEWER);
        LocalDate today = today(a.tenant());
        ownerJdbc().update("update agreement set renewal_date = ?, notice_period_days = 15 where id = ?",
                today.plusDays(40), a.agreementId());
        job.runOne(a.tenant());
        var rows = visible(a.tenant(), a.owner());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("title").toString()).startsWith("Renewal decision due for").contains("in 25 day(s)");
        assertThat(rows.get(0).get("dedupe_key").toString())
                .startsWith("EXPIRY:AGREEMENT_RENEWAL:" + a.agreementId() + ":" + today.plusDays(25) + ":").endsWith(":30");
    }

    @Test
    void aCancelledAgreementIsNeverReminded() {
        var a = agreement("ex-agr-cancelled", AGR_VIEWER, AGR_VIEWER);
        ownerJdbc().update("update agreement set expires_at = ?, renewal_date = ?, status = 'CANCELLED', "
                + "cancel_reason = 'replaced' where id = ?", today(a.tenant()).plusDays(5), today(a.tenant()).plusDays(5),
                a.agreementId());
        job.runOne(a.tenant());
        assertThat(expiryRows(a.tenant(), a.owner())).isEmpty();
        assertThat(expiryRows(a.tenant(), a.caseOwner())).isEmpty();
    }

    @Test
    void anAlreadyExpiredAgreementIsNotReminded() {
        var a = agreement("ex-agr-past", AGR_VIEWER, AGR_VIEWER);
        ownerJdbc().update("update agreement set expires_at = ? where id = ?", today(a.tenant()).minusDays(1), a.agreementId());
        job.runOne(a.tenant());
        assertThat(expiryRows(a.tenant(), a.owner())).isEmpty();
    }

    @Test
    void anAgreementOnAHeldCaseIsNotRemindedAbout() {
        var a = agreement("ex-agr-held", AGR_VIEWER, AGR_VIEWER);
        ownerJdbc().update("update agreement set expires_at = ? where id = ?", today(a.tenant()).plusDays(5), a.agreementId());
        ownerJdbc().update("update onboarding_case set status = 'ON_HOLD', held_at = now() where id = ?", a.caseId());
        job.runOne(a.tenant());
        assertThat(expiryRows(a.tenant(), a.owner())).isEmpty();
    }

    @Test
    void aCaseOwnerWithNarrowAgreementScopeGetsNothingButTheAgreementOwnerDoes() {
        var a = agreement("ex-agr-scope", Map.of(PermissionKeys.AGREEMENT_VIEW, Scope.ASSIGNED),
                Map.of(PermissionKeys.AGREEMENT_VIEW, Scope.TEAM));
        // ASSIGNED on an agreement resolves through an active case participant row, not the owner column
        fixture.runAs(a.tenant(), () -> journey.addParticipant(a.tenant(), a.caseId(), a.owner(),
                RelationshipType.OWNER, ParticipantStatus.ACTIVE));
        ownerJdbc().update("update agreement set expires_at = ? where id = ?", today(a.tenant()).plusDays(5), a.agreementId());
        job.runOne(a.tenant());
        assertThat(expiryRows(a.tenant(), a.caseOwner())).isEmpty();
        assertThat(visible(a.tenant(), a.owner())).hasSize(1);
    }

    @Test
    void aPortalOwnedAgreementIsNeverRemindedBeforeOrAfterSend() {
        UUID t = tenant("ex-agr-portal");
        UUID caseId = fixture.runAsReturning(t, () ->
                agreementSupport.openCaseWithSignatureRequirement(t, AgreementRecordMode.STRUCTURED_ONLY));
        UUID agreementId = fixture.runAsReturning(t, () -> agreementRepository.findByCaseId(caseId).get(0).getId());
        UUID customerId = ownerJdbc().queryForObject("select customer_id from onboarding_case where id = ?", UUID.class, caseId);
        UUID portal = fixture.createPortalUserForContact(t, customerId, "p+" + Uuid7.generate() + "@portal.example");
        ownerJdbc().update("update agreement set owner_user_id = ?, expires_at = ? where id = ?", portal,
                today(t).plusDays(5), agreementId);      // still DRAFT: pre-SENT
        job.runOne(t);
        assertThat(expiryRows(t, portal)).isEmpty();
        ownerJdbc().update("update agreement set status = 'SENT' where id = ?", agreementId);
        job.runOne(t);
        assertThat(expiryRows(t, portal)).isEmpty();
    }
}
