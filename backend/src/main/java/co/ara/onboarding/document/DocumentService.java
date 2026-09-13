package co.ara.onboarding.document;

import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * First real business logic in {@code document} (Task 14) -- read paths only.
 * Every read goes through {@link AuthorizedQuery}, which resolves scope
 * against {@code scoping.DocumentDescriptor} and then ANDs in
 * {@code scoping.DocumentAudienceFilter}'s targeting/sharing predicate on top
 * (Tasks 9-13, already built and reviewed; this is the first time either runs
 * through a real service method rather than a descriptor/filter unit test).
 *
 * {@link #forCase}'s home-or-linked filter is a plain {@code extra}
 * Specification, layered ON TOP of -- never replacing or bypassing -- the
 * scope+audience predicate {@link AuthorizedQuery#findAll} already applies.
 * That split matters: the audience answers WHO may see a document at all
 * (targeting/sharing), this filter answers WHICH CASE'S view of the document
 * list it belongs in (its home case, or a case it has been explicitly linked
 * into via {@link DocumentCaseLink}) -- two different questions, so this
 * stays a caller-supplied filter rather than being folded into either the
 * descriptor or the audience filter.
 *
 * Known, already-recorded gap this task does not fix (Task 10's own review):
 * {@code DocumentDescriptor}'s DEPARTMENT/TEAM/ASSIGNED scope predicates match
 * only a document's HOME case, not any case it is linked into -- so a
 * DEPARTMENT-scoped reader of a case a document is merely LINKED into (not
 * its home case) sees it only if also ALL-scoped, until Task 20 revisits the
 * descriptor. {@link DocumentServiceTest} exercises {@link #forCase}'s own
 * filter at ALL scope specifically to isolate it from this separate question.
 */
@Service
public class DocumentService {

    private final DocumentRepository documents;
    private final AuthorizedQuery authorizedQuery;

    public DocumentService(DocumentRepository documents, AuthorizedQuery authorizedQuery) {
        this.documents = documents;
        this.authorizedQuery = authorizedQuery;
    }

    @RequirePermission(PermissionKeys.DOCUMENT_VIEW)
    @Transactional(readOnly = true)
    public Page<DocumentView> list(Pageable pageable) {
        return authorizedQuery.findAll(documents, Document.class, PermissionKeys.DOCUMENT_VIEW, null, pageable)
                .map(DocumentService::toView);
    }

    /**
     * Every document whose home case is {@code caseId}, plus every document
     * linked into it via a LIVE {@link DocumentCaseLink} ({@code revokedAt IS
     * NULL}) -- a revoked link excludes the document from this case's list
     * again, the same "unlink is a column, not a DELETE" shape
     * {@link DocumentCaseLink}'s own doc comment describes.
     */
    @RequirePermission(PermissionKeys.DOCUMENT_VIEW)
    @Transactional(readOnly = true)
    public Page<DocumentView> forCase(UUID caseId, Pageable pageable) {
        Specification<Document> homeOrLinked = (root, query, cb) ->
                cb.or(cb.equal(root.get("caseId"), caseId), linkedInto(root, query, cb, caseId));
        return authorizedQuery.findAll(documents, Document.class, PermissionKeys.DOCUMENT_VIEW, homeOrLinked, pageable)
                .map(DocumentService::toView);
    }

    @RequirePermission(PermissionKeys.DOCUMENT_VIEW)
    @Transactional(readOnly = true)
    public DocumentView get(UUID id) {
        return toView(authorizedQuery.getById(documents, Document.class, PermissionKeys.DOCUMENT_VIEW, id));
    }

    /** An EXISTS subquery over document_case_link, live links into caseId only. */
    private Predicate linkedInto(Root<Document> root, CriteriaQuery<?> query, CriteriaBuilder cb, UUID caseId) {
        var subquery = query.subquery(UUID.class);
        var link = subquery.from(DocumentCaseLink.class);
        subquery.select(link.get("id")).where(cb.and(
                cb.equal(link.get("documentId"), root.get("id")),
                cb.equal(link.get("caseId"), caseId),
                cb.isNull(link.get("revokedAt"))));
        return cb.exists(subquery);
    }

    private static DocumentView toView(Document d) {
        return new DocumentView(d.getId(), d.getCaseId(), d.getCustomerId(), d.getName(),
                d.getCategory(), d.getVisibilityTier(), d.getTargetDepartmentId(), d.getTargetContactLabel(),
                d.getOwnerContactId(), d.getExpiresAt(), d.getStatus(), d.getCurrentVersionId(),
                d.getUploadedBy(), d.getCreatedAt(), d.getUpdatedAt());
    }
}
