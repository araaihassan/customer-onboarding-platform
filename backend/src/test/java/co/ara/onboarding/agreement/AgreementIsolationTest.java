package co.ara.onboarding.agreement;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WriteScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cross-tenant negatives (the {@code task.TaskIsolationTest} shape): an agreement in tenant A is
 * invisible to tenant B's FULL-authority administrator -- every read and write answers
 * {@link NoSuchElementException} (404), never a success, a 403 or a 500. RLS plus
 * {@code AuthorizedQuery}, not scope, is what is under test, so the actor holds every permission.
 */
class AgreementIsolationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired AgreementTestSupport support;
    @Autowired AgreementService agreementService;
    @Autowired AgreementReviewService reviewService;
    @Autowired AgreementSignatureService signatureService;

    private record World(UUID tenantA, UUID tenantB, UUID adminB, AgreementTestSupport.Driven sent,
                         AgreementTestSupport.Driven underReview) {}

    /** Tenant A: one SENT and one UNDER_REVIEW agreement. Tenant B: just its fully-privileged administrator. */
    private World world(String slugPrefix) {
        UUID a = fixture.createTenant(slugPrefix + "-a");
        UUID b = fixture.createTenant(slugPrefix + "-b");
        UUID adminB = fixture.createAdministrator(b, "admin-b+" + Uuid7.generate() + "@example.com");
        var caseSent = new UUID[1];
        var caseReview = new UUID[1];
        fixture.runAs(a, () -> {
            caseSent[0] = support.openCase(a, WriteScope.ANY, null, null, null);
            caseReview[0] = support.openCase(a, WriteScope.ANY, null, null, null);
        });
        var sent = support.drive(a, caseSent[0], AgreementStatus.SENT, 1, null, null);
        var review = support.drive(a, caseReview[0], AgreementStatus.UNDER_REVIEW, 1, null, null);
        return new World(a, b, adminB, sent, review);
    }

    private void asB(World w, Runnable action) {
        fixture.runAsUser(w.tenantB(), w.adminB(), action);
    }

    @Test
    void getListForCaseAndSummaryDoNotLeakAcrossTenants() {
        World w = world("agr-iso-read");

        assertThatThrownBy(() -> asB(w, () -> agreementService.get(w.sent().agreementId())))
                .isInstanceOf(NoSuchElementException.class);

        var listed = new AtomicReference<List<AgreementView>>();
        var forCase = new AtomicReference<List<AgreementView>>();
        var summary = new AtomicReference<AgreementSummaryView>();
        asB(w, () -> {
            listed.set(agreementService.list(null, Pageable.unpaged()).getContent());
            forCase.set(agreementService.forCase(w.sent().caseId()));
            summary.set(agreementService.summary());
        });
        assertThat(listed.get()).isEmpty();
        assertThat(forCase.get()).isEmpty();
        assertThat(summary.get()).isEqualTo(new AgreementSummaryView(0, 0, 0, 0, 0, 0));

        // Control: the owning tenant does see them, so the emptiness above is isolation, not an empty fixture.
        var own = new AtomicReference<List<AgreementView>>();
        fixture.runAs(w.tenantA(), () -> own.set(agreementService.forCase(w.sent().caseId())));
        assertThat(own.get()).hasSize(1);
    }

    @Test
    void everyWriteRefusesACrossTenantAgreementId() {
        World w = world("agr-iso-write");
        UUID sentId = w.sent().agreementId();
        UUID reviewId = w.underReview().agreementId();
        long sentLock = w.sent().lockVersion();
        long reviewLock = w.underReview().lockVersion();
        var before = List.of(support.snapshot(w.tenantA(), sentId), support.snapshot(w.tenantA(), reviewId));

        List<Runnable> writes = List.of(
                () -> agreementService.patch(sentId, new PatchAgreementRequest(
                        "Hijacked", null, null, null, null, null, sentLock)),
                () -> agreementService.replaceSignatories(sentId, new ReplaceSignatoriesRequest(List.of(), sentLock)),
                () -> agreementService.uploadDraftFile(sentId, sentLock, new ByteArrayInputStream(new byte[] {1}), 1),
                () -> agreementService.submit(sentId, sentLock),
                () -> reviewService.review(reviewId, 1,
                        new ReviewAgreementRequest(ReviewDecision.APPROVE, null, reviewLock)),
                () -> agreementService.send(sentId, sentLock),
                () -> signatureService.record(sentId, new RecordSignatureRequest(
                        w.sent().signatoryIds().get(0), LocalDate.now(clock), "Wet ink", sentLock), null, 0),
                () -> agreementService.cancel(sentId, new CancelAgreementRequest("Hijack", sentLock)));

        for (Runnable write : writes) {
            assertThatThrownBy(() -> asB(w, write)).isInstanceOf(NoSuchElementException.class);
        }

        // No side effects: tenant A's rows are exactly as they were.
        assertThat(List.of(support.snapshot(w.tenantA(), sentId), support.snapshot(w.tenantA(), reviewId)))
                .isEqualTo(before);
    }

    @Test
    void aCrossTenantSignatoryContactOrUserIdIsNotFound() {
        UUID a = fixture.createTenant("agr-iso-party-a");
        UUID b = fixture.createTenant("agr-iso-party-b");
        UUID adminB = fixture.createAdministrator(b, "admin-b+" + Uuid7.generate() + "@example.com");
        var foreignContact = new UUID[1];
        var foreignUser = new UUID[1];
        fixture.runAs(a, () -> {
            UUID customerA = fixture.createCustomer(a, "Customer A", null, null, null);
            foreignContact[0] = fixture.createContact(a, customerA, "contact-a+" + Uuid7.generate() + "@example.com");
            foreignUser[0] = fixture.createUser(a, "user-a+" + Uuid7.generate() + "@example.com");
        });
        var draft = new Agreement[1];
        fixture.runAs(b, () -> draft[0] = support.draftAgreementRow(b));
        long lock = draft[0].getLockVersion();
        UUID id = draft[0].getId();

        assertThatThrownBy(() -> fixture.runAsUser(b, adminB, () -> agreementService.replaceSignatories(id,
                new ReplaceSignatoriesRequest(List.of(new SignatoryRequest(
                        SignatoryKind.CONTACT, foreignContact[0], null, "Signer")), lock))))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> fixture.runAsUser(b, adminB, () -> agreementService.replaceSignatories(id,
                new ReplaceSignatoriesRequest(List.of(new SignatoryRequest(
                        SignatoryKind.INTERNAL, null, foreignUser[0], "Signer")), lock))))
                .isInstanceOf(NoSuchElementException.class);

        var after = support.snapshot(b, id);
        assertThat(after.signatories()).isZero();
        assertThat(after.lockVersion()).isEqualTo(lock);
    }
}
