package co.ara.onboarding.document;

import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.customer.OrgUnitResolver;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.StageWriteScopeGuard;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.platform.storage.BlobStore;
import co.ara.onboarding.platform.storage.StorageProperties;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.NoSuchElementException;
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

    /** A magic-byte/structural sniff needs only a small prefix, never the whole file. */
    private static final int SNIFF_PREFIX_BYTES = 8192;

    private static final String DOCUMENT_VERSION_NO_UNIQUE = "document_version_no_uq";

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final CaseRepository cases;
    private final StageRepository stages;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final StageWriteScopeGuard writeScope;
    private final BlobStore blobStore;
    private final StorageProperties storageProperties;
    private final ContentSniffGuard sniffGuard;
    private final OrgUnitResolver orgUnits;
    private final Clock clock;

    public DocumentService(DocumentRepository documents, DocumentVersionRepository versions,
                           CaseRepository cases, StageRepository stages,
                           AuthorizedQuery authorizedQuery, AuthContextProvider contextProvider,
                           StageWriteScopeGuard writeScope, BlobStore blobStore,
                           StorageProperties storageProperties, ContentSniffGuard sniffGuard,
                           OrgUnitResolver orgUnits, Clock clock) {
        this.documents = documents;
        this.versions = versions;
        this.cases = cases;
        this.stages = stages;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.writeScope = writeScope;
        this.blobStore = blobStore;
        this.storageProperties = storageProperties;
        this.sniffGuard = sniffGuard;
        this.orgUnits = orgUnits;
        this.clock = clock;
    }

    /**
     * Runs whether or not anything ever calls {@link #upload} -- a Spring
     * singleton is instantiated eagerly at context refresh, the same reasoning
     * {@code auth.JwtProperties}'s own {@code @PostConstruct} javadoc gives for
     * why its guard cannot be removed by a later refactor that makes the
     * gated operation lazy. Deliberately NOT on {@link StorageProperties}
     * itself (contrast its own doc comment): that class is wired into
     * {@code StorageConfigTest}'s narrow, Postgres-free contexts to prove
     * {@code app.storage.kind}'s own guard, and a blanket failure here would
     * fire for every one of those even though none of them ever touches
     * document upload.
     */
    @PostConstruct
    void validateMaxUploadBytes() {
        if (storageProperties.getMaxUploadBytes() == null) {
            throw new IllegalStateException(
                    "app.storage.max-upload-bytes is not set. Every upload's declared size is"
                            + " checked against this ceiling before its stream is ever touched, so a"
                            + " deployment that forgot it would accept uploads of unbounded size with"
                            + " no error anywhere -- there is no implied default. Set it, in bytes"
                            + " (for example: 26214400 for 25 MiB).");
        }
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
     *
     * Resolves {@code caseId} through {@link AuthorizedQuery} FIRST, under
     * {@code document.view} itself (never {@code case.view}) -- the same
     * "confirm the parent is visible before listing its children" idiom
     * {@code task.TaskService.forCase}, {@code journey.ApprovalService.listForCase}
     * and {@code journey.PlanRevisionService.listForCase} all already use, each
     * under their OWN gating permission rather than {@code case.view}, so an
     * out-of-scope {@code caseId} is a 404 here too, rather than a silently
     * empty page. Safe for an INTERNAL actor: {@code document.view}'s
     * DEPARTMENT/TEAM scope ({@code scoping.DocumentDescriptor}) resolves off
     * the SAME case-ownership columns {@code case.view}'s own descriptor does,
     * so this can never resolve a case that actor's document scope would not
     * also reach.
     *
     * NOT safe unconditionally, which is why the PORTAL branch below exists.
     * {@code PortalPermissions} grants {@code document.view} at {@code Scope.ALL}
     * -- a code constant, not a catalog scope -- and {@code Case} has no
     * {@link co.ara.onboarding.authz.AudienceFilter} registered (only
     * {@link Document} does). {@code AuthorizationPredicateBuilder.scopePredicate}
     * short-circuits ALL to an unconditional match with nothing left to narrow
     * it, so resolving {@code caseId} under {@code document.view} for a portal
     * actor would resolve ANY case in the tenant -- turning today's uniformly
     * empty result for an unreachable case into a 404-vs-200 existence oracle
     * over every case, the same shape {@code AuthorizationCoverageTest.
     * FINDER_RULE_EXCLUSIONS}'s own doc comment already records once for
     * {@code TaskDirectoryAdapter} ("CASE_VIEW and TASK_VIEW are different
     * permissions held at different scopes"). Spec §8 never actually routes a
     * portal caller here anyway -- {@code GET /portal/documents} takes no
     * {@code caseId} -- so this is a fail-closed guard on the method's own
     * contract, not a path expected to fire in production.
     */
    @RequirePermission(PermissionKeys.DOCUMENT_VIEW)
    @Transactional(readOnly = true)
    public Page<DocumentView> forCase(UUID caseId, Pageable pageable) {
        if (contextProvider.current().userType() == UserType.PORTAL) {
            throw new NoSuchElementException("Not found");
        }
        authorizedQuery.getById(cases, Case.class, PermissionKeys.DOCUMENT_VIEW, caseId);

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

    /**
     * Task 15: a new document, uploaded against {@code caseId}. Blob first, row
     * second (spec §7.4/§5.1) -- {@link #captureContent} writes the blob before
     * either row is inserted, and the whole method is one transaction, so any
     * failure after that point (an unexpected constraint violation on the
     * document row, say) rolls back both rows together, leaving nothing visible
     * that points at a blob never actually written, and nothing corrupted by an
     * orphaned one that was.
     *
     * {@code customerId} is copied from the RESOLVED case -- never accepted in
     * {@link CreateDocumentRequest}, so there is no field a caller could even
     * send to override it (CLAUDE.md's write-path invariant). {@code caseId}
     * itself is resolved through {@link AuthorizedQuery} under
     * {@code document.upload} before anything is written, the same
     * "confirm the parent is visible first" idiom {@code task.TaskService}
     * already uses. {@link StageWriteScopeGuard} then narrows on top, the same
     * way it already does for {@code task} (see {@link #applyWriteScope}).
     *
     * <p>NOT safe unconditionally for a PORTAL actor -- the same gap
     * {@link #forCase} already guards against, and a strictly worse shape
     * here because this is a WRITE. {@code PortalPermissions} grants
     * {@code document.upload} at {@code Scope.ALL} to both contacts and
     * sponsors, and {@code Case} has no {@link co.ara.onboarding.authz.AudienceFilter}
     * registered -- {@code AuthorizationPredicateBuilder.scopePredicate}
     * short-circuits ALL to an unconditional match, so resolving {@code caseId}
     * under {@code document.upload} for a portal actor would resolve ANY case
     * in the tenant, letting a portal contact of customer A create a
     * {@code Document} row on customer B's case (with {@code customerId}
     * copied straight from B). Today this is only accidentally masked when
     * {@code currentStageId} is non-null, because {@link #applyWriteScope}'s
     * own {@code Stage} lookup is gated {@code WORKFLOW_VIEW} and 404s a
     * portal actor -- a case with a null {@code currentStageId} (no guard at
     * all) would sail straight through. Refused explicitly here, before the
     * case lookup runs, rather than relying on that accident. Task 26 (the
     * real portal upload endpoint) is what eventually replaces this with
     * genuine narrowing -- spec §8 never routes a portal caller through this
     * exact method signature anyway. {@code addVersion} does NOT need this
     * guard: its {@code caseId} comes from an already-audience-filtered
     * {@link Document}, never from the caller directly.
     */
    @RequirePermission(PermissionKeys.DOCUMENT_UPLOAD)
    @Transactional
    public DocumentView upload(UUID caseId, CreateDocumentRequest request,
                               InputStream content, long sizeBytes, String declaredContentType) {
        if (contextProvider.current().userType() == UserType.PORTAL) {
            throw new NoSuchElementException("Not found");
        }
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.DOCUMENT_UPLOAD, caseId);
        applyWriteScope(c);

        UUID actor = contextProvider.current().userId();
        StoredContent stored = captureContent(request.category(), content, sizeBytes);

        Document d = new Document();
        d.setId(Uuid7.generate());
        d.setTenantId(c.getTenantId());
        d.setCaseId(c.getId());
        d.setCustomerId(c.getCustomerId());
        d.setName(request.name());
        d.setCategory(request.category());
        d.setVisibilityTier(request.visibilityTier());
        d.setTargetDepartmentId(orgUnits.resolveDepartment(request.targetDepartmentId()));
        d.setTargetContactLabel(request.targetContactLabel());
        d.setOwnerContactId(request.ownerContactId());
        d.setExpiresAt(request.expiresAt());
        d.setStatus(DocumentStatus.ACTIVE);
        d.setUploadedBy(actor);
        // Reassigned, not discarded: Document's id is assigned in Java, not
        // database-generated, so Spring Data's save() merges rather than
        // persists -- merge() returns a DIFFERENT managed instance from the
        // transient one passed in, with created_at/updated_at now populated
        // by BaseEntity's own @PrePersist. Continuing to mutate the ORIGINAL
        // (still-detached, still-null-timestamped) reference and saving it
        // again would merge those nulls straight back over the real values on
        // the second call -- exactly the "created_at violates not-null"
        // failure this reassignment avoids.
        d = documents.saveAndFlush(d);

        DocumentVersion v = new DocumentVersion(Uuid7.generate(), c.getTenantId(), d.getId(), 1,
                stored.storageKey(), sizeBytes, stored.contentType(), stored.sha256(),
                ReviewStatus.PENDING, actor, Instant.now(clock));
        versions.saveAndFlush(v);

        d.setCurrentVersionId(v.getId());
        d = documents.saveAndFlush(d);

        return toView(d);
    }

    /**
     * Task 15: a new version of an EXISTING document. {@code documentId} is
     * resolved through {@link AuthorizedQuery} under {@code document.upload}
     * first -- at ASSIGNED scope this is the document's own {@code uploaded_by}
     * column (scoping.DocumentDescriptor), so an ASSIGNED-scoped holder may only
     * add a version to a document they themselves originally uploaded. The
     * case is then re-resolved (never trusted from the already-loaded Document
     * row without going back through AuthorizedQuery) purely so
     * {@link StageWriteScopeGuard} narrows on top, exactly as {@link #upload}
     * does.
     *
     * {@code version_no} is the current max plus one; two callers racing this
     * computation can both land on the same number, which
     * {@code document_version_no_uq} then refuses as a 409
     * ({@link DocumentVersionConflictException}) rather than a silent
     * overwrite -- there is no row lock here the way
     * {@code CaseRepository.lockById} serialises {@code CaseEngine.reconcile},
     * because appending a version derives no state (spec §4.2). A new version
     * always starts {@code PENDING}, regardless of any earlier version's own
     * review outcome -- approving v1 says nothing about v2 (spec §5.1).
     */
    @RequirePermission(PermissionKeys.DOCUMENT_UPLOAD)
    @Transactional
    public DocumentVersionView addVersion(UUID documentId, InputStream content,
                                          long sizeBytes, String declaredContentType) {
        Document d = authorizedQuery.getById(documents, Document.class, PermissionKeys.DOCUMENT_UPLOAD, documentId);
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.DOCUMENT_UPLOAD, d.getCaseId());
        applyWriteScope(c);

        UUID actor = contextProvider.current().userId();
        StoredContent stored = captureContent(d.getCategory(), content, sizeBytes);

        int nextVersionNo = versions.maxVersionNo(d.getId()) + 1;
        DocumentVersion v = new DocumentVersion(Uuid7.generate(), d.getTenantId(), d.getId(), nextVersionNo,
                stored.storageKey(), sizeBytes, stored.contentType(), stored.sha256(),
                ReviewStatus.PENDING, actor, Instant.now(clock));
        try {
            versions.saveAndFlush(v);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, DOCUMENT_VERSION_NO_UNIQUE)) {
                throw new DocumentVersionConflictException(d.getId(), e);
            }
            // Every other constraint is rethrown untouched -- reporting an
            // unrelated violation as a version race would send the caller
            // hunting for a conflict that does not exist.
            throw e;
        }

        d.setCurrentVersionId(v.getId());
        d = documents.saveAndFlush(d);

        return toVersionView(v);
    }

    /**
     * The stage write_scope guard, applied to a case rather than a milestone --
     * {@code document} attaches only to a case, so there is no Milestone to
     * pass {@code StageWriteScopeGuard}'s original three-argument
     * {@code check}, only its Task 15 {@code check(Case, Stage)} overload.
     * Reads the CASE's own current stage (never a milestone's) because that is the
     * only "where in the journey is this write happening" a document has.
     * A case with no current stage yet has nothing to narrow against, so this
     * is a no-op rather than a refusal -- the guard is subtractive only, and
     * "nothing to subtract from" is not itself a reason to refuse.
     */
    private void applyWriteScope(Case c) {
        if (c.getCurrentStageId() == null) return;
        Stage stage = authorizedQuery.getById(stages, Stage.class, PermissionKeys.WORKFLOW_VIEW, c.getCurrentStageId());
        writeScope.check(c, stage);
    }

    /**
     * The size ceiling, the sniffed-content MIME check and the SHA-256 digest,
     * all three from Task 7's ruling (spec §2.3/§7.6), applied to one stream
     * read exactly once:
     *
     * <ol>
     *   <li>The declared {@code sizeBytes} is checked against
     *       {@code app.storage.max-upload-bytes} before the stream is touched
     *       at all -- no I/O, no blob, no row.</li>
     *   <li>A bounded PREFIX (enough for magic-byte detection) is read into a
     *       byte array and sniffed. A rejection at this point has touched
     *       nothing else -- no blob write, no row.</li>
     *   <li>The prefix is replayed via {@link SequenceInputStream} ahead of the
     *       stream's own remainder -- not a second read from the source, a
     *       replay of the bytes already buffered followed by the rest of the
     *       SAME stream -- wrapped in a {@link DigestInputStream} before
     *       {@link BlobStore#put} ever sees it. The bytes sniffed, hashed and
     *       written are therefore provably identical: one read, start to
     *       end.</li>
     * </ol>
     */
    private StoredContent captureContent(DocumentCategory category, InputStream content, long sizeBytes) {
        enforceSizeCeiling(sizeBytes);

        byte[] prefix = readPrefix(content, SNIFF_PREFIX_BYTES);
        String sniffedType = sniffGuard.detect(prefix);
        sniffGuard.enforce(category, sniffedType);

        MessageDigest digest = sha256();
        InputStream combined = new SequenceInputStream(new ByteArrayInputStream(prefix), content);
        DigestInputStream digestStream = new DigestInputStream(combined, digest);

        // BlobStore.put takes ownership of digestStream and closes it, reading
        // it fully -- which is what finishes updating digest with every byte.
        String storageKey = blobStore.put(digestStream, sizeBytes, sniffedType);
        String sha256Hex = HexFormat.of().formatHex(digest.digest());

        return new StoredContent(storageKey, sniffedType, sha256Hex);
    }

    private void enforceSizeCeiling(long sizeBytes) {
        long max = storageProperties.getMaxUploadBytes();
        if (sizeBytes > max) throw new UploadTooLargeException(sizeBytes, max);
    }

    private static byte[] readPrefix(InputStream in, int max) {
        try {
            byte[] buf = new byte[max];
            int total = 0;
            int r;
            while (total < max && (r = in.read(buf, total, max - total)) != -1) {
                total += r;
            }
            return total == max ? buf : Arrays.copyOf(buf, total);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read upload prefix", e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Matched on the constraint name Hibernate reports, not on message text,
     * which is Postgres's to reword -- the same idiom
     * {@code programme.ProgrammeMembershipService.violates} and
     * {@code customer.CustomerContactService.violates} both already use.
     */
    private static boolean violates(Throwable failure, String constraintName) {
        for (Throwable t = failure; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && constraintName.equals(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private record StoredContent(String storageKey, String contentType, String sha256) {}

    private static DocumentVersionView toVersionView(DocumentVersion v) {
        return new DocumentVersionView(v.getId(), v.getDocumentId(), v.getVersionNo(), v.getSizeBytes(),
                v.getContentType(), v.getSha256(), v.getReviewStatus(), v.getReviewedBy(), v.getReviewedAt(),
                v.getReviewNote(), v.getUploadedBy(), v.getUploadedAt());
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
