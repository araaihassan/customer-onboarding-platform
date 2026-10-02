package co.ara.onboarding.agreement;

import co.ara.onboarding.document.DocumentContentService;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.AgreementRecordMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.io.ByteArrayInputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 20: the portal read service. Visibility is {@code AgreementAudienceFilter}'s (SENT onward,
 * own customer, never CANCELLED); this class proves the service rides it, narrows its view, and
 * defends itself against a non-portal caller.
 */
class AgreementPortalTest extends PostgresTestBase {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    @Autowired TenantFixture fixture;
    @Autowired AgreementTestSupport support;
    @Autowired AgreementRepository agreements;
    @Autowired AgreementService agreementService;
    @Autowired AgreementReviewService reviewService;
    @Autowired PortalAgreementService portal;
    @Autowired DocumentContentService content;

    /** A tenant with one case (so one customer), a portal user of that customer, and the case id. */
    private record World(UUID tenant, UUID caseId, UUID customerId, UUID portalUser) {}

    private World world(String slug, AgreementRecordMode mode) {
        UUID tenant = fixture.createTenant(slug + "-" + Uuid7.generate());
        var caseId = new UUID[1];
        var customerId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = support.openCaseWithSignatureRequirement(tenant, mode);
            customerId[0] = agreements.findByCaseId(caseId[0]).get(0).getCustomerId();
        });
        UUID portalUser = fixture.createPortalUserForContact(
                tenant, customerId[0], "portal+" + Uuid7.generate() + "@example.com");
        return new World(tenant, caseId[0], customerId[0], portalUser);
    }

    private UUID rowIn(World w, AgreementStatus status) {
        var id = new UUID[1];
        fixture.runAs(w.tenant, () -> id[0] = support.agreementRowInStatus(w.tenant, w.caseId, status).getId());
        return id[0];
    }

    private List<PortalAgreementView> mineAs(World w) {
        var out = new AtomicReference<List<PortalAgreementView>>();
        fixture.runAsUser(w.tenant, w.portalUser, () -> out.set(portal.mine()));
        return out.get();
    }

    @Test
    void nothingIsVisibleInDraftUnderReviewOrApproved() {
        World w = world("agr-portal-hidden", AgreementRecordMode.STRUCTURED_ONLY);
        UUID draft = rowIn(w, AgreementStatus.DRAFT);
        UUID review = rowIn(w, AgreementStatus.UNDER_REVIEW);
        UUID approved = rowIn(w, AgreementStatus.APPROVED);

        assertThat(mineAs(w)).isEmpty();
        for (UUID id : List.of(draft, review, approved)) {
            assertThatThrownBy(() -> fixture.runAsUser(w.tenant, w.portalUser, () -> portal.get(id)))
                    .isInstanceOf(NoSuchElementException.class);
        }
    }

    @Test
    void theAgreementIsVisibleFromSentOnward() {
        World w = world("agr-portal-visible", AgreementRecordMode.STRUCTURED_ONLY);
        UUID sent = rowIn(w, AgreementStatus.SENT);
        UUID awaiting = rowIn(w, AgreementStatus.AWAITING_SIGNATURE);
        UUID signed = rowIn(w, AgreementStatus.SIGNED);

        assertThat(mineAs(w)).extracting(PortalAgreementView::id).containsExactlyInAnyOrder(sent, awaiting, signed);
        var got = new AtomicReference<PortalAgreementView>();
        fixture.runAsUser(w.tenant, w.portalUser, () -> got.set(portal.get(signed)));
        assertThat(got.get().displayStatus()).isEqualTo(AgreementDisplayStatus.SIGNED);
        assertThat(got.get().signedAt()).isNotNull();
        assertThat(got.get().caseId()).isEqualTo(w.caseId);
    }

    @Test
    void anotherCustomersAgreementIsNeverVisible() {
        World a = world("agr-portal-cross", AgreementRecordMode.STRUCTURED_ONLY);
        // A second customer's case in the SAME tenant, with a SENT agreement.
        var otherCase = new UUID[1];
        fixture.runAs(a.tenant, () -> otherCase[0] =
                support.openCaseWithSignatureRequirement(a.tenant, AgreementRecordMode.STRUCTURED_ONLY));
        var otherId = new UUID[1];
        fixture.runAs(a.tenant, () -> otherId[0] =
                support.agreementRowInStatus(a.tenant, otherCase[0], AgreementStatus.SENT).getId());

        assertThat(mineAs(a)).isEmpty();
        assertThatThrownBy(() -> fixture.runAsUser(a.tenant, a.portalUser, () -> portal.get(otherId[0])))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void aCancelledAgreementIsNotVisible() {
        World w = world("agr-portal-cancelled", AgreementRecordMode.STRUCTURED_ONLY);
        UUID cancelled = rowIn(w, AgreementStatus.CANCELLED);

        assertThat(mineAs(w)).isEmpty();
        assertThatThrownBy(() -> fixture.runAsUser(w.tenant, w.portalUser, () -> portal.get(cancelled)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void theViewCarriesNoInternalFields() {
        Set<String> forbidden = Set.of("reviewer", "reviewerId", "reason", "recordedBy", "cancelReason",
                "contactId", "userId", "ownerUserId", "lastEditedBy", "submittedBy", "customerId",
                "requirementId", "signatoryId", "lockVersion", "signatureProvider", "providerEnvelopeId");
        List<String> names = new ArrayList<>();
        for (RecordComponent c : PortalAgreementView.class.getRecordComponents()) names.add(c.getName());
        for (Class<?> nested : PortalAgreementView.class.getDeclaredClasses()) {
            if (nested.isRecord()) Arrays.stream(nested.getRecordComponents()).forEach(n -> names.add(n.getName()));
        }
        assertThat(names).doesNotContainAnyElementsOf(forbidden);
        assertThat(names).contains("displayRole", "signed", "signedOn");
    }

    /** A FILE_BACKED agreement with a real uploaded PDF, driven to APPROVED or SENT. */
    private record FileAgreement(UUID agreementId, UUID documentId, long lockVersion, UUID editor) {}

    private FileAgreement fileAgreementThrough(World w, boolean send) {
        UUID editor = fixture.createAdministrator(w.tenant, "editor+" + Uuid7.generate() + "@example.com");
        UUID reviewer = fixture.createAdministrator(w.tenant, "reviewer+" + Uuid7.generate() + "@example.com");
        var agreement = new Agreement[1];
        var signer = new UUID[1];
        fixture.runAs(w.tenant, () -> {
            agreement[0] = agreements.findByCaseId(w.caseId).get(0);
            signer[0] = fixture.createUser(w.tenant, "signer+" + Uuid7.generate() + "@example.com");
        });
        UUID id = agreement[0].getId();
        var latest = new AgreementDetailView[1];
        fixture.runAsUser(w.tenant, editor, () -> latest[0] = agreementService.replaceSignatories(id,
                new ReplaceSignatoriesRequest(List.of(new SignatoryRequest(
                        SignatoryKind.INTERNAL, null, signer[0], "Signer")), agreement[0].getLockVersion())));
        fixture.runAsUser(w.tenant, editor, () -> latest[0] = agreementService.patch(id,
                new PatchAgreementRequest(null, LocalDate.of(2026, 10, 1), null, null, null, null,
                        latest[0].agreement().lockVersion())));
        fixture.runAsUser(w.tenant, editor, () -> latest[0] = agreementService.uploadDraftFile(id,
                latest[0].agreement().lockVersion(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));
        fixture.runAsUser(w.tenant, editor, () -> latest[0] = agreementService.submit(
                id, latest[0].agreement().lockVersion()));
        fixture.runAsUser(w.tenant, reviewer, () -> latest[0] = reviewService.review(id,
                latest[0].versions().get(0).versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.APPROVE, null, latest[0].agreement().lockVersion())));
        if (send) {
            fixture.runAsUser(w.tenant, editor, () -> latest[0] = agreementService.send(
                    id, latest[0].agreement().lockVersion()));
        }
        return new FileAgreement(id, latest[0].agreement().documentId(), latest[0].agreement().lockVersion(), editor);
    }

    private void openFileAsPortal(World w, UUID documentId) {
        fixture.runAsUser(w.tenant, w.portalUser, () -> {
            var blob = content.open(documentId, 1);
            try (var in = blob.content()) {
                assertThat(in.readAllBytes()).isEqualTo(PDF_BYTES);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void theAgreementFileIsNotDownloadableBeforeSend() {
        World w = world("agr-portal-file-pre", AgreementRecordMode.FILE_BACKED);
        FileAgreement f = fileAgreementThrough(w, false);

        assertThat(f.documentId()).isNotNull();
        assertThatThrownBy(() -> openFileAsPortal(w, f.documentId())).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void theAgreementFileIsDownloadableAfterSend() {
        World w = world("agr-portal-file-sent", AgreementRecordMode.FILE_BACKED);
        FileAgreement f = fileAgreementThrough(w, true);

        openFileAsPortal(w, f.documentId());   // must not throw
        var got = new AtomicReference<PortalAgreementView>();
        fixture.runAsUser(w.tenant, w.portalUser, () -> got.set(portal.get(f.agreementId())));
        assertThat(got.get().documentId()).isEqualTo(f.documentId());
        assertThat(got.get().sentVersionNumber()).isEqualTo(1);
        assertThat(got.get().sentContentSha256()).hasSize(64);
        assertThat(got.get().signatories()).hasSize(1);
        assertThat(got.get().signatories().get(0).signed()).isFalse();
    }

    @Test
    void theAgreementFileStopsBeingDownloadableWhenASentAgreementIsCancelled() {
        World w = world("agr-portal-file-cancel", AgreementRecordMode.FILE_BACKED);
        FileAgreement f = fileAgreementThrough(w, true);
        openFileAsPortal(w, f.documentId());

        fixture.runAsUser(w.tenant, f.editor(), () -> agreementService.cancel(
                f.agreementId(), new CancelAgreementRequest("Wrong terms", f.lockVersion())));

        assertThatThrownBy(() -> openFileAsPortal(w, f.documentId())).isInstanceOf(NoSuchElementException.class);
        assertThat(mineAs(w)).isEmpty();
    }

    @Test
    void anInternalUserCallingThePortalServiceIsNotFound() {
        World w = world("agr-portal-internal", AgreementRecordMode.STRUCTURED_ONLY);
        UUID sent = rowIn(w, AgreementStatus.SENT);
        UUID admin = fixture.createAdministrator(w.tenant, "admin+" + Uuid7.generate() + "@example.com");

        assertThatThrownBy(() -> fixture.runAsUser(w.tenant, admin, () -> portal.mine()))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> fixture.runAsUser(w.tenant, admin, () -> portal.get(sent)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void aRetiredContactSeesNothing() {
        World w = world("agr-portal-retired", AgreementRecordMode.STRUCTURED_ONLY);
        UUID sent = rowIn(w, AgreementStatus.SENT);
        assertThat(mineAs(w)).extracting(PortalAgreementView::id).containsExactly(sent);

        fixture.retireContactFor(w.tenant, w.portalUser);

        // Either the gate refuses (no live contact => no grants) or the filter returns nothing;
        // what must never happen is the agreement coming back.
        try {
            assertThat(mineAs(w)).isEmpty();
        } catch (AccessDeniedException expected) {
            // denied outright: equally "sees nothing"
        }
        try {
            fixture.runAsUser(w.tenant, w.portalUser, () -> portal.get(sent));
            org.junit.jupiter.api.Assertions.fail("a retired contact must not read an agreement");
        } catch (NoSuchElementException | AccessDeniedException expected) {
            // fine
        }
    }
}
