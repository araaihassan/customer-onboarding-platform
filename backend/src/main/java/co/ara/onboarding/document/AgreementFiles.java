package co.ara.onboarding.document;

import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.StageWriteScopeGuard;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Sub-project 5, spec section 7: every document write an agreement needs, through one
 * narrow facade gated on agreement permissions -- so an agreement author needs no
 * document.* permission, and an agreement's file is mutable only via its agreement.
 * Names no agreement type (ModuleBoundaryTest.noDocumentDependencyOnAgreement): the
 * gates are authz permission keys, and the category check below is how it recognises
 * "an agreement's document" without knowing what an agreement is.
 *
 * <p>Documents are created SENSITIVE: that hides them from every portal contact absent
 * an explicit share ({@code scoping.DocumentAudienceFilter#portalAudience}) while
 * leaving internal case readers untouched, so a DRAFT agreement's file cannot leak
 * through the existing portal document endpoints. Sending the agreement retiers it
 * COMPANY_SHARED; cancelling a sent agreement retiers it back -- both are the
 * {@code agreement} module's own job, calling {@link #retier} on this class.
 *
 * <p>Every write here goes through {@link DocumentContentWriter} (Task 7) rather than
 * forking a parallel copy of the size-ceiling/sniff/digest hardening -- the exact
 * defect a prior security review already found and fixed once for document uploads.
 *
 * <p><b>Audit:</b> this class records nothing. The agreement action recorded by the
 * caller ({@code agreement.submitted} / {@code .signature_recorded} / {@code .sent})
 * is the business event; a {@code document.uploaded} for a SENSITIVE agreement draft
 * would put a customer-invisible file on the customer-visible timeline.
 */
@Component
public class AgreementFiles {

    private final DocumentRepository documents;
    private final CaseRepository cases;
    private final StageRepository stages;
    private final AuthorizedQuery authorizedQuery;
    private final StageWriteScopeGuard writeScope;
    private final AuthContextProvider contextProvider;
    private final DocumentContentWriter writer;
    private final DocumentVersionRepository versions;

    public AgreementFiles(DocumentRepository documents, CaseRepository cases, StageRepository stages,
                          AuthorizedQuery authorizedQuery, StageWriteScopeGuard writeScope,
                          AuthContextProvider contextProvider, DocumentContentWriter writer,
                          DocumentVersionRepository versions) {
        this.documents = documents;
        this.cases = cases;
        this.stages = stages;
        this.authorizedQuery = authorizedQuery;
        this.writeScope = writeScope;
        this.contextProvider = contextProvider;
        this.writer = writer;
        this.versions = versions;
    }

    /**
     * Creates a new AGREEMENT-category document, SENSITIVE and untargeted, on the
     * given case -- the agreement's very first draft file. {@code caseId} is resolved
     * through {@link AuthorizedQuery} under {@code agreement.manage} itself (never
     * {@code case.view}/{@code document.upload}), so a caller needs no document
     * permission at all to draft an agreement's file.
     */
    @RequirePermission(PermissionKeys.AGREEMENT_MANAGE)
    @Transactional
    public OwnedFile createOwnedDocument(UUID caseId, String name, InputStream content, long sizeBytes) {
        refusePortal();
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.AGREEMENT_MANAGE, caseId);
        applyWriteScope(c);
        UUID actor = contextProvider.current().userId();
        var stored = writer.capture(DocumentCategory.AGREEMENT, content, sizeBytes);
        Document d = writer.createDocument(c, actor, name, DocumentCategory.AGREEMENT, VisibilityTier.SENSITIVE,
                null, null, null, null, stored, sizeBytes);
        return new OwnedFile(d.getId(), d.getCurrentVersionId(), 1, stored.sha256());
    }

    /** A revised draft, before submission -- gated the same as creation. */
    @RequirePermission(PermissionKeys.AGREEMENT_MANAGE)
    @Transactional
    public OwnedFile addDraftVersion(UUID documentId, InputStream content, long sizeBytes) {
        return append(documentId, PermissionKeys.AGREEMENT_MANAGE, content, sizeBytes);
    }

    /** The countersigned copy -- gated on recording a signature, never on drafting authority. */
    @RequirePermission(PermissionKeys.AGREEMENT_SIGN_RECORD)
    @Transactional
    public OwnedFile addCountersignedVersion(UUID documentId, InputStream content, long sizeBytes) {
        return append(documentId, PermissionKeys.AGREEMENT_SIGN_RECORD, content, sizeBytes);
    }

    /**
     * Flips the document's visibility tier -- SENSITIVE before send, COMPANY_SHARED
     * from send onward, never CONTACT_ONLY: an agreement's document only ever has
     * two states, unlike a document a portal contact can otherwise upload with any
     * of the three tiers.
     */
    @RequirePermission(PermissionKeys.AGREEMENT_MANAGE)
    @Transactional
    public void retier(UUID documentId, VisibilityTier tier) {
        if (tier != VisibilityTier.SENSITIVE && tier != VisibilityTier.COMPANY_SHARED) {
            throw new IllegalArgumentException("An agreement's document is SENSITIVE or COMPANY_SHARED, never " + tier);
        }
        Document d = ownedDocument(documentId, PermissionKeys.AGREEMENT_MANAGE);
        d.setVisibilityTier(tier);
        documents.saveAndFlush(d);
    }

    /**
     * The current (highest-numbered) version of an agreement's own document, for
     * {@code AgreementService.submit} to freeze onto its new {@code AgreementVersion}
     * row -- gated on {@code agreement.manage} like every other write here, since
     * submit is itself a draft-editing write until the moment it flips status.
     */
    @RequirePermission(PermissionKeys.AGREEMENT_MANAGE)
    public OwnedFile currentVersion(UUID documentId) {
        Document d = ownedDocument(documentId, PermissionKeys.AGREEMENT_MANAGE);
        int versionNo = versions.maxVersionNo(d.getId());
        DocumentVersion v = versions.versionAt(d.getId(), versionNo)
                .orElseThrow(() -> new NoSuchElementException("Not found"));
        return new OwnedFile(d.getId(), v.getId(), v.getVersionNo(), v.getSha256());
    }

    private OwnedFile append(UUID documentId, String permission, InputStream content, long sizeBytes) {
        refusePortal();
        Document d = ownedDocument(documentId, permission);
        Case c = authorizedQuery.getById(cases, Case.class, permission, d.getCaseId());
        applyWriteScope(c);
        var stored = writer.capture(DocumentCategory.AGREEMENT, content, sizeBytes);
        DocumentVersion v = writer.appendVersion(d, contextProvider.current().userId(), stored, sizeBytes);
        return new OwnedFile(d.getId(), v.getId(), v.getVersionNo(), stored.sha256());
    }

    /**
     * Resolved through {@link AuthorizedQuery} like any id; a non-AGREEMENT document
     * is simply not an agreement's file -- 404, not 400, so this facade can never be
     * used to touch a document it has no business knowing about, even one the caller
     * could otherwise read through {@code document.*}.
     */
    private Document ownedDocument(UUID documentId, String permission) {
        Document d = authorizedQuery.getById(documents, Document.class, permission, documentId);
        if (d.getCategory() != DocumentCategory.AGREEMENT) throw new NoSuchElementException("Not found");
        return d;
    }

    /** Same idiom as {@code DocumentService#upload}'s own portal refusal -- see its own javadoc. */
    private void refusePortal() {
        if (contextProvider.current().userType() == UserType.PORTAL) throw new NoSuchElementException("Not found");
    }

    /** Same guard, same reasoning, as {@code DocumentRequestService#applyWriteScope} -- see its own javadoc. */
    private void applyWriteScope(Case c) {
        if (c.getCurrentStageId() == null) return;
        Stage stage = authorizedQuery.getById(stages, Stage.class, PermissionKeys.WORKFLOW_VIEW, c.getCurrentStageId());
        writeScope.check(c, stage);
    }
}
