package co.ara.onboarding.agreement;

import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.customer.ContactStatus;
import co.ara.onboarding.customer.CustomerContact;
import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.document.DocumentCategory;
import co.ara.onboarding.document.DocumentRepository;
import co.ara.onboarding.document.DocumentVersionRepository;
import co.ara.onboarding.document.VisibilityTier;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.Milestone;
import co.ara.onboarding.journey.Requirement;
import co.ara.onboarding.journey.WriteScopeException;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.AgreementRecordMode;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WriteScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.signature;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 12: draft editing -- {@link AgreementService#patch}, {@link
 * AgreementService#replaceSignatories} and {@link AgreementService#uploadDraftFile}.
 * {@code AgreementTestSupport} (Task 3/4) supplies every fixture row this class builds
 * on, same as {@code AgreementReadTest}.
 */
class AgreementDraftTest extends PostgresTestBase {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired AgreementTestSupport support;
    @Autowired AgreementService agreementService;
    @Autowired AgreementRepository agreements;
    @Autowired AgreementSignatoryRepository signatories;
    @Autowired CustomerContactRepository contacts;
    @Autowired DocumentRepository documents;
    @Autowired DocumentVersionRepository documentVersions;
    @Autowired RoleService roles;
    @Autowired AuthContextProvider contextProvider;

    @Test
    void patchChangesOnlySuppliedFieldsAndSetsLastEditedBy() {
        UUID tenant = fixture.createTenant("agr-draft-patch-supplied");
        var agreementId = new UUID[1];
        var originalName = new String[1];
        var lockVersion = new long[1];
        var actor = new UUID[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            originalName[0] = a.getName();
            lockVersion[0] = a.getLockVersion();
            actor[0] = contextProvider.principal().userId();
        });

        var result = fixture.runAsReturning(tenant, () -> agreementService.patch(agreementId[0],
                new PatchAgreementRequest(null, LocalDate.of(2027, 1, 1), null, null, null, Set.of(), lockVersion[0])));

        assertThat(result.agreement().name()).isEqualTo(originalName[0]);
        assertThat(result.agreement().effectiveDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(result.agreement().expiresAt()).isNull();
        assertThat(result.agreement().renewalDate()).isNull();
        assertThat(result.agreement().noticePeriodDays()).isNull();
        assertThat(result.agreement().lastEditedBy()).isEqualTo(actor[0]);
    }

    @Test
    void patchClearsANamedField() {
        UUID tenant = fixture.createTenant("agr-draft-patch-clear");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
        });

        var withDate = fixture.runAsReturning(tenant, () -> agreementService.patch(agreementId[0],
                new PatchAgreementRequest(null, LocalDate.of(2027, 2, 2), null, null, null, Set.of(), lockVersion[0])));
        assertThat(withDate.agreement().effectiveDate()).isEqualTo(LocalDate.of(2027, 2, 2));

        var cleared = fixture.runAsReturning(tenant, () -> agreementService.patch(agreementId[0],
                new PatchAgreementRequest(null, null, null, null, null,
                        Set.of(ClearableAgreementField.EFFECTIVE_DATE), withDate.agreement().lockVersion())));
        assertThat(cleared.agreement().effectiveDate()).isNull();
    }

    @Test
    void aFieldBothSuppliedAndClearedIsRefused() {
        UUID tenant = fixture.createTenant("agr-draft-patch-conflict");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
        });

        assertThatThrownBy(() -> fixture.runAsReturning(tenant, () -> agreementService.patch(agreementId[0],
                new PatchAgreementRequest(null, LocalDate.of(2027, 3, 3), null, null, null,
                        Set.of(ClearableAgreementField.EFFECTIVE_DATE), lockVersion[0]))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void patchWithAStaleLockVersionIsAConflict() {
        UUID tenant = fixture.createTenant("agr-draft-patch-stale");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
        });

        assertThatThrownBy(() -> fixture.runAsReturning(tenant, () -> agreementService.patch(agreementId[0],
                new PatchAgreementRequest(null, LocalDate.of(2027, 4, 4), null, null, null,
                        Set.of(), lockVersion[0] + 1))))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void everyDraftWriteIsRefusedOutsideDraft() {
        UUID tenant = fixture.createTenant("agr-draft-status-guard");
        for (AgreementStatus status : List.of(AgreementStatus.UNDER_REVIEW, AgreementStatus.APPROVED,
                AgreementStatus.SENT, AgreementStatus.SIGNED, AgreementStatus.CANCELLED)) {
            var agreementId = new UUID[1];
            var lockVersion = new long[1];
            fixture.runAs(tenant, () -> {
                Case c = journey.newCase(tenant);
                Agreement a = support.agreementRowInStatus(tenant, c.getId(), status);
                agreementId[0] = a.getId();
                lockVersion[0] = a.getLockVersion();
            });

            assertThatThrownBy(() -> fixture.runAsReturning(tenant, () -> agreementService.patch(agreementId[0],
                    new PatchAgreementRequest(null, LocalDate.of(2027, 5, 5), null, null, null,
                            Set.of(), lockVersion[0]))))
                    .as("status " + status)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void replaceSignatoriesSwapsTheWholeListInOrder() {
        UUID tenant = fixture.createTenant("agr-draft-sig-swap");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var firstUserId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            firstUserId[0] = fixture.createUser(tenant, "first+" + Uuid7.generate() + "@example.com");
        });

        var afterFirst = fixture.runAsReturning(tenant, () -> agreementService.replaceSignatories(agreementId[0],
                new ReplaceSignatoriesRequest(
                        List.of(new SignatoryRequest(SignatoryKind.INTERNAL, null, firstUserId[0], "Sole Approver")),
                        lockVersion[0])));
        assertThat(afterFirst.signatories()).hasSize(1);

        var contactId = new UUID[1];
        var userId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Agreement a = agreements.findById(agreementId[0]).orElseThrow();
            contactId[0] = fixture.createContact(tenant, a.getCustomerId(), "customer+" + Uuid7.generate() + "@example.com");
            userId[0] = fixture.createUser(tenant, "internal+" + Uuid7.generate() + "@example.com");
        });

        var afterSecond = fixture.runAsReturning(tenant, () -> agreementService.replaceSignatories(agreementId[0],
                new ReplaceSignatoriesRequest(List.of(
                        new SignatoryRequest(SignatoryKind.CONTACT, contactId[0], null, "Customer Signatory"),
                        new SignatoryRequest(SignatoryKind.INTERNAL, null, userId[0], "Internal Approver")),
                        afterFirst.agreement().lockVersion())));

        assertThat(afterSecond.signatories()).hasSize(2);
        assertThat(afterSecond.signatories().get(0).kind()).isEqualTo(SignatoryKind.CONTACT);
        assertThat(afterSecond.signatories().get(0).contactId()).isEqualTo(contactId[0]);
        assertThat(afterSecond.signatories().get(0).sortOrder()).isEqualTo(0);
        assertThat(afterSecond.signatories().get(1).kind()).isEqualTo(SignatoryKind.INTERNAL);
        assertThat(afterSecond.signatories().get(1).userId()).isEqualTo(userId[0]);
        assertThat(afterSecond.signatories().get(1).sortOrder()).isEqualTo(1);
        assertThat(afterSecond.signatories()).extracting(AgreementSignatoryView::userId)
                .doesNotContain(firstUserId[0]);
    }

    @Test
    void aContactOfAnotherCustomerIsNotFound() {
        UUID tenant = fixture.createTenant("agr-draft-sig-cross-customer");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var contactId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            UUID otherCustomerId = fixture.createCustomer(tenant, "Other Co " + Uuid7.generate(), null, null, null);
            contactId[0] = fixture.createContact(tenant, otherCustomerId, "outsider+" + Uuid7.generate() + "@example.com");
        });

        assertThatThrownBy(() -> fixture.runAsReturning(tenant, () -> agreementService.replaceSignatories(agreementId[0],
                new ReplaceSignatoriesRequest(
                        List.of(new SignatoryRequest(SignatoryKind.CONTACT, contactId[0], null, "Wrong Customer")),
                        lockVersion[0]))))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void aRetiredContactIsRefusedAsASignatory() {
        UUID tenant = fixture.createTenant("agr-draft-sig-retired");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var contactId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            contactId[0] = fixture.createContact(tenant, a.getCustomerId(), "retired+" + Uuid7.generate() + "@example.com");
            CustomerContact contact = contacts.findById(contactId[0]).orElseThrow();
            contact.setStatus(ContactStatus.INACTIVE);
            contacts.saveAndFlush(contact);
        });

        assertThatThrownBy(() -> fixture.runAsReturning(tenant, () -> agreementService.replaceSignatories(agreementId[0],
                new ReplaceSignatoriesRequest(
                        List.of(new SignatoryRequest(SignatoryKind.CONTACT, contactId[0], null, "Retired Contact")),
                        lockVersion[0]))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anInternalSignatoryOutsideUserViewScopeIsNotFound() {
        UUID tenant = fixture.createTenant("agr-draft-sig-user-oos");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
        });

        UUID bogusUserId = Uuid7.generate();
        assertThatThrownBy(() -> fixture.runAsReturning(tenant, () -> agreementService.replaceSignatories(agreementId[0],
                new ReplaceSignatoriesRequest(
                        List.of(new SignatoryRequest(SignatoryKind.INTERNAL, null, bogusUserId, "Ghost")),
                        lockVersion[0]))))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void aSignatoryWhoseKindDisagreesWithItsIdsIsRefused() {
        UUID tenant = fixture.createTenant("agr-draft-sig-mismatch");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var contactId = new UUID[1];
        var userId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            contactId[0] = fixture.createContact(tenant, a.getCustomerId(), "mismatch-contact+" + Uuid7.generate() + "@example.com");
            userId[0] = fixture.createUser(tenant, "mismatch-user+" + Uuid7.generate() + "@example.com");
        });

        // CONTACT carrying a userId as well -- a shape the database's own
        // agreement_signatory_party_ck refuses too, but this must be a 400 before
        // any row is even attempted.
        assertThatThrownBy(() -> fixture.runAsReturning(tenant, () -> agreementService.replaceSignatories(agreementId[0],
                new ReplaceSignatoriesRequest(
                        List.of(new SignatoryRequest(SignatoryKind.CONTACT, contactId[0], userId[0], "Bad Shape")),
                        lockVersion[0]))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSameContactTwiceIsRefusedBeforeTheDatabaseSeesIt() {
        UUID tenant = fixture.createTenant("agr-draft-sig-dup");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var contactId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            contactId[0] = fixture.createContact(tenant, a.getCustomerId(), "dup+" + Uuid7.generate() + "@example.com");
        });

        assertThatThrownBy(() -> fixture.runAsReturning(tenant, () -> agreementService.replaceSignatories(agreementId[0],
                new ReplaceSignatoriesRequest(List.of(
                        new SignatoryRequest(SignatoryKind.CONTACT, contactId[0], null, "First"),
                        new SignatoryRequest(SignatoryKind.CONTACT, contactId[0], null, "Second")),
                        lockVersion[0]))))
                .isInstanceOf(IllegalArgumentException.class);

        fixture.runAs(tenant, () -> assertThat(signatories.ofAgreement(agreementId[0])).isEmpty());
    }

    @Test
    void uploadingTheFirstDraftFileCreatesTheOwnedSensitiveDocument() {
        UUID tenant = fixture.createTenant("agr-draft-upload-first");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
        });

        var result = fixture.runAsReturning(tenant, () -> agreementService.uploadDraftFile(agreementId[0], lockVersion[0],
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));

        assertThat(result.agreement().documentId()).isNotNull();

        fixture.runAs(tenant, () -> {
            Document d = documents.findById(result.agreement().documentId()).orElseThrow();
            assertThat(d.getCategory()).isEqualTo(DocumentCategory.AGREEMENT);
            assertThat(d.getVisibilityTier()).isEqualTo(VisibilityTier.SENSITIVE);
            assertThat(documentVersions.maxVersionNo(d.getId())).isEqualTo(1);
        });
    }

    @Test
    void uploadingAgainAddsAVersionToTheSameDocument() {
        UUID tenant = fixture.createTenant("agr-draft-upload-again");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
        });

        var first = fixture.runAsReturning(tenant, () -> agreementService.uploadDraftFile(agreementId[0], lockVersion[0],
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));
        UUID documentId = first.agreement().documentId();

        var second = fixture.runAsReturning(tenant, () -> agreementService.uploadDraftFile(agreementId[0],
                first.agreement().lockVersion(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));

        assertThat(second.agreement().documentId()).isEqualTo(documentId);
        fixture.runAs(tenant, () -> assertThat(documentVersions.maxVersionNo(documentId)).isEqualTo(2));
    }

    @Test
    void uploadingAFileToAStructuredOnlyAgreementIsRefused() {
        UUID tenant = fixture.createTenant("agr-draft-upload-structured");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        fixture.runAs(tenant, () -> {
            UUID caseId = support.openCaseWithSignatureRequirement(tenant, AgreementRecordMode.STRUCTURED_ONLY);
            Agreement a = agreements.findByCaseId(caseId).get(0);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
        });

        assertThatThrownBy(() -> fixture.runAsReturning(tenant, () -> agreementService.uploadDraftFile(agreementId[0],
                lockVersion[0], new ByteArrayInputStream("x".getBytes()), 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Narrowest scope (CLAUDE.md): a TEAM-scoped agreement.manage holder on their team's own case. */
    @Test
    void aTeamScopedManagerCanEditAnAgreementOnTheirTeamsCase() {
        UUID tenant = fixture.createTenant("agr-draft-team-scope");
        var teamActor = new UUID[1];
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        fixture.runAs(tenant, () -> {
            UUID teamId = fixture.createTeam(tenant, "Delivery Team " + Uuid7.generate());
            teamActor[0] = fixture.createUser(tenant, "team-scoped+" + Uuid7.generate() + "@example.com");
            fixture.addToTeam(tenant, teamActor[0], teamId);
            grant(teamActor[0], Map.of(
                    PermissionKeys.AGREEMENT_MANAGE, Scope.TEAM,
                    PermissionKeys.AGREEMENT_VIEW, Scope.TEAM,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            Case c = journey.newCase(tenant, null, null, teamId);
            Milestone m = journey.newMilestone(tenant, c);
            Requirement r = journey.newRequirement(tenant, c, m);
            Agreement a = support.draftAgreementRowFor(tenant, c.getId(), r.getId());
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
        });

        var resultRef = new AtomicReference<AgreementDetailView>();
        fixture.runAsUser(tenant, teamActor[0], () -> resultRef.set(agreementService.patch(agreementId[0],
                new PatchAgreementRequest(null, LocalDate.of(2027, 6, 6), null, null, null, Set.of(), lockVersion[0]))));

        assertThat(resultRef.get().agreement().effectiveDate()).isEqualTo(LocalDate.of(2027, 6, 6));
    }

    /**
     * The widest possible record-level grant (ALL, on agreement.manage/view) is still
     * refused inside an OWNER_ONLY stage the actor has no ownership relationship to --
     * StageWriteScopeGuard narrows on top of, never instead of, the record-level scope a
     * permission is held at. Same shape as {@code task.TaskWriteScopeTest
     * .aWiderScopedHolderIsStillRefusedInsideAnOwnerOnlyStage}.
     */
    @Test
    void aWiderScopedManagerIsRefusedInsideAnOwnerOnlyStage() {
        UUID tenant = fixture.createTenant("agr-draft-owner-only");
        var wideActor = new UUID[1];
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        fixture.runAs(tenant, () -> {
            wideActor[0] = fixture.createUser(tenant, "wide+" + Uuid7.generate() + "@example.com");
            grant(wideActor[0], Map.of(
                    PermissionKeys.AGREEMENT_MANAGE, Scope.ALL,
                    PermissionKeys.AGREEMENT_VIEW, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID caseOwner = fixture.createUser(tenant, "owner+" + Uuid7.generate() + "@example.com");

            var restrictedStage = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Restricted Stage", null, false, true, true, null,
                    WriteScope.OWNER_ONLY, null, null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(),
                            List.of(signature("Sign it", AgreementRecordMode.FILE_BACKED, "Fixture Agreement")))),
                    List.of());
            UUID versionId = journey.publish(
                    new WorkflowDefinitionRequest(List.of(restrictedStage), List.of(), 0L));

            UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), caseOwner, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(), Map.of())).id();

            Agreement a = agreements.findByCaseId(caseId).get(0);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, wideActor[0], () -> agreementService.patch(agreementId[0],
                new PatchAgreementRequest(null, LocalDate.of(2027, 7, 7), null, null, null, Set.of(), lockVersion[0]))))
                .isInstanceOf(WriteScopeException.class);
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }
}
