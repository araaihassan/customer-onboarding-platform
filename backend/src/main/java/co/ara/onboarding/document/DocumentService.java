package co.ara.onboarding.document;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.PortalContactDirectory;
import co.ara.onboarding.authz.PortalContactFacts;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.customer.CustomerContact;
import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.customer.OrgUnitResolver;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.RequirementRepository;
import co.ara.onboarding.journey.RequirementService;
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
import org.springframework.data.domain.PageRequest;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
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
 * Task 10's own review found a gap here, closed by Task 20:
 * {@code DocumentDescriptor}'s DEPARTMENT/TEAM scope predicates now match a
 * document through its HOME case OR any case it is currently LINKED into via
 * a live {@link DocumentCaseLink} row ({@code DocumentDescriptor.viaLinkedCase}),
 * so a DEPARTMENT-scoped reader of a case a document is merely linked into no
 * longer needs to also be ALL-scoped to see it. {@code assignedScope} stays
 * HOME-case-only, and deliberately so -- unlike DEPARTMENT/TEAM it never
 * widened: it reads the document's own {@code uploaded_by} column, a personal
 * relationship (the {@code RelationshipType} invariant: "ASSIGNED means a
 * personal relationship... access mediated by a team the user belongs to is
 * TEAM"), never mediated by either case. {@link DocumentServiceTest} exercises
 * {@link #forCase}'s own filter at ALL scope specifically to isolate it from
 * this separate question.
 */
@Service
public class DocumentService {

    /** A magic-byte/structural sniff needs only a small prefix, never the whole file. */
    private static final int SNIFF_PREFIX_BYTES = 8192;

    private static final String DOCUMENT_VERSION_NO_UNIQUE = "document_version_no_uq";

    /**
     * Task 18's own convention for {@code Requirement.satisfiedRefType}
     * (lowercase, matching {@code task.TaskService}'s existing
     * {@code requirements.satisfy(requirementId, taskId, "task")} call site
     * exactly) -- this is the FIRST place in sub-project 4 that touches
     * {@code satisfiedRefType} at all. Tasks 24/25 (document requirement
     * instantiation and fulfilment) must reuse this exact constant rather
     * than inventing a second string for the same concept.
     */
    static final String SATISFIED_REF_TYPE = "document";

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentShareRepository shares;
    private final DocumentCaseLinkRepository caseLinks;
    private final CaseRepository cases;
    private final StageRepository stages;
    private final RequirementRepository requirementRepository;
    private final RequirementService requirementService;
    private final CustomerContactRepository contacts;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final PortalContactDirectory portalContacts;
    private final StageWriteScopeGuard writeScope;
    private final BlobStore blobStore;
    private final StorageProperties storageProperties;
    private final ContentSniffGuard sniffGuard;
    private final OrgUnitResolver orgUnits;
    private final Clock clock;
    private final AuditRecorder audit;

    public DocumentService(DocumentRepository documents, DocumentVersionRepository versions,
                           DocumentShareRepository shares, DocumentCaseLinkRepository caseLinks,
                           CaseRepository cases, StageRepository stages,
                           RequirementRepository requirementRepository, RequirementService requirementService,
                           CustomerContactRepository contacts,
                           AuthorizedQuery authorizedQuery, AuthContextProvider contextProvider,
                           PortalContactDirectory portalContacts,
                           StageWriteScopeGuard writeScope, BlobStore blobStore,
                           StorageProperties storageProperties, ContentSniffGuard sniffGuard,
                           OrgUnitResolver orgUnits, Clock clock, AuditRecorder audit) {
        this.documents = documents;
        this.versions = versions;
        this.shares = shares;
        this.caseLinks = caseLinks;
        this.cases = cases;
        this.stages = stages;
        this.requirementRepository = requirementRepository;
        this.requirementService = requirementService;
        this.contacts = contacts;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.portalContacts = portalContacts;
        this.writeScope = writeScope;
        this.blobStore = blobStore;
        this.storageProperties = storageProperties;
        this.sniffGuard = sniffGuard;
        this.orgUnits = orgUnits;
        this.clock = clock;
        this.audit = audit;
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

    /**
     * Task 18's own review finding (deferred from Task 14): a RETIRED
     * document must not appear in EITHER listing, so {@code notRetired()} is
     * ANDed onto the scope+audience predicate here exactly as it is in
     * {@link #forCase}. {@link #get} deliberately does NOT carry this filter
     * -- a retired document stays reachable by id (it is a business record,
     * never deleted, spec 7.1), just no longer listed.
     *
     * <p><b>Task 32:</b> {@code visibilityTier}, when supplied, narrows the
     * result to that one tier -- ANDed onto {@link #notRetired()} the same
     * compositional way {@link #expiring} already ANDs its own extra
     * predicate on top ({@link #withTier}). {@code null} means "every tier",
     * matching this method's own pre-existing contract for every caller that
     * predates this parameter. This is also the filter context {@link
     * #visibilitySummary} must be bounded to -- the `docs` screen's five
     * scope-filter buttons (`SCREENS.md` §7) and its hidden-count line are
     * two views of the SAME query, so they take the same parameter and must
     * never drift apart on what "the current filter" means.
     */
    @RequirePermission(PermissionKeys.DOCUMENT_VIEW)
    @Transactional(readOnly = true)
    public Page<DocumentView> list(VisibilityTier visibilityTier, Pageable pageable) {
        return authorizedQuery.findAll(documents, Document.class, PermissionKeys.DOCUMENT_VIEW,
                        withTier(notRetired(), visibilityTier), pageable)
                .map(DocumentService::toView);
    }

    /**
     * Task 32: the codebase's SECOND deliberate authorization bypass -- the
     * first and, until now, only one is {@code journey.TimelineService} via
     * {@code audit.AuditQuery} ({@code AuthorizationCoverageTest}'s own
     * documented carve-out). Spec §9 names this the "08 VISIBLE · 61 HIDDEN
     * BY SCOPE" line on the `docs` screen: without it, a scoped view (a
     * DEPARTMENT-scoped reader, say) that happens to see zero rows is
     * visually indistinguishable from a tenant that genuinely has no
     * documents at all -- CLAUDE.md's own `taskSummary` finding is the shape
     * of leak this line must not repeat, so this is written as a bounded
     * aggregate, not a repeat of that gap.
     *
     * <p><b>Corrected mechanism, review round 1.</b> The first version of
     * this method bypassed {@code AuthorizedQuery} entirely for {@code total}
     * (a direct {@code documents.count(filter)} repository call), which a
     * security review proved unsafe live: a portal contact holds {@code
     * document.view} at {@code Scope.ALL} ({@code authz.PortalPermissions}'
     * own javadoc states, in capitals, this is safe ONLY because {@code
     * scoping.DocumentAudienceFilter} narrows every other read reaching that
     * grant), and a full bypass skipped that narrowing too -- so a portal
     * contact of customer A could read a {@code total} that included
     * customer B's documents in the SAME tenant, a disclosure RLS does
     * nothing to stop (RLS is tenant isolation, not audience narrowing). The
     * fix, now what this method actually does: {@code total} bypasses ONLY
     * the record-level SCOPE union (DEPARTMENT/TEAM/ASSIGNED), through
     * {@link AuthorizedQuery#countIgnoringScope} /
     * {@code AuthorizationPredicateBuilder#forPermissionIgnoringScope} -- the
     * AUDIENCE filter ({@code scoping.DocumentAudienceFilter}) still applies
     * on top, exactly as it does for {@code visible}'s fully-authorized
     * {@link #list}. That is what collapses a portal actor's own {@code
     * total} back down to their own customer (closing the disclosure above),
     * and an internal ALL-scoped reader's {@code total} down to documents
     * actually targeted at them or generally shared -- design spec §10
     * invariant 5, "the audience filter binds ALL", holds for this read too
     * now, not just for {@link #list}/{@link #forCase}.
     *
     * <p><b>Why disclosing this ONE integer is still safe</b> with the scope
     * union bypassed: {@code total} is a bare {@code COUNT}, bounded to the
     * exact SAME filter ({@code visibilityTier} plus {@link #notRetired()})
     * the caller already supplied and already sees the {@code visible} half
     * of through the fully-authorized {@link #list} -- never a document's own
     * id, name, category, target, or any other field. This is the same shape
     * {@code AuthorizationCoverageTest}'s own comment already establishes as
     * safe for {@code journey.TimelineService}'s carve-out ("narrowed to one
     * resource id... never row content"): here the narrowing is "bounded to
     * the caller's own filter context, AND still audience-narrowed" rather
     * than "one resource id", but the safety argument is the same one --
     * a caller learns only THAT more matching, audience-visible documents
     * exist somewhere in the tenant and roughly how many, never which ones,
     * who uploaded them, or what they are about. Postgres RLS ({@code
     * app.tenant_id}) still confines {@code total} to the current tenant
     * regardless of the scope-union bypass -- the same safety net {@code
     * journey.TimelineService}'s own carve-out relies on -- so what remains
     * bypassed after this fix is a same-tenant, same-audience record-level
     * SCOPE widening only, never a cross-tenant or cross-audience one.
     *
     * <p>{@code visible} deliberately uses {@code PageRequest.of(0, 1)}, NOT
     * {@code Pageable.unpaged()}: {@link Page#getTotalElements()} always
     * issues Spring Data's own backing {@code COUNT} query regardless of page
     * size, so requesting a one-row page still gets the real total while
     * fetching (and mapping to {@link DocumentView}) only one row of content
     * nobody here needs -- {@code unpaged()} would instead materialise and
     * fetch EVERY matching row purely to read its count.
     *
     * <p>{@code hidden} is clamped to never go negative ({@code Math.max(0,
     * total - visible)}). Under Postgres READ COMMITTED, {@code visible} and
     * {@code total} are two separate statements in the same transaction, so a
     * concurrent write between them (a document uploaded or retired by
     * someone else, mid-computation) can in principle let {@code total}
     * observe a slightly different snapshot than {@code visible} did a moment
     * earlier -- which could transiently push the raw difference to -1. This
     * is a rare, self-correcting-on-refresh anomaly, never a security
     * concern (both numbers still come from the same tenant, the same
     * filter and audience narrowing, and disclose nothing about individual
     * rows either way) -- the clamp exists purely so the UI is never asked
     * to render a nonsensical negative count.
     *
     * <p>Verified against {@code AuthorizationCoverageTest}'s finder-rule
     * regex (binds on {@code findAll}/{@code findOne}/{@code findById}/
     * {@code findBy*} by name): {@link DocumentRepository#count(Specification)}
     * -- now reached only through {@link AuthorizedQuery#countIgnoringScope},
     * never directly -- is named {@code count}, which that predicate does not
     * match, so this call needed no new exclusion in that test -- confirmed
     * by running it, not assumed. The bypass itself is recorded in that
     * test's own "deliberate, reviewed instances" enumeration comment (sixth
     * entry, added alongside this fix), not just here.
     */
    @RequirePermission(PermissionKeys.DOCUMENT_VIEW)
    @Transactional(readOnly = true)
    public DocumentVisibilitySummaryView visibilitySummary(VisibilityTier visibilityTier) {
        Specification<Document> filter = withTier(notRetired(), visibilityTier);

        long visible = authorizedQuery.findAll(documents, Document.class, PermissionKeys.DOCUMENT_VIEW,
                        filter, PageRequest.of(0, 1))
                .getTotalElements();

        // Deliberate scope-union bypass, audience filter still applied --
        // see this method's own javadoc above for the full safety argument
        // and the review-round-1 finding that made this NOT a full bypass.
        long total = authorizedQuery.countIgnoringScope(documents, Document.class, PermissionKeys.DOCUMENT_VIEW, filter);

        long hidden = Math.max(0, total - visible);
        return new DocumentVisibilitySummaryView(visible, hidden);
    }

    /**
     * Task 28: every document (across every case) whose {@code expires_at} is
     * set and falls at or before {@code now + within} -- the read half of
     * design spec's expiry seam. <b>Nothing fires here</b>: this stores and
     * exposes {@code expires_at}; sub-project 6 owns the actual notification
     * (design spec's own scope table names this explicitly). No controller
     * method calls this either -- the 37-task plan names no frontend hook or
     * endpoint for it, unlike Tasks 23/25's own late-discovered HTTP surfaces.
     *
     * <p>{@code now} is read from the injected {@link Clock}, matching every
     * other timestamp this class computes ({@link #persistNewDocument},
     * {@link #addVersion}) rather than a bare {@code Instant.now()} --
     * CLAUDE.md's own "asserted a due date against the bare, zero-arg
     * {@code LocalDate.now()}... instead of {@code LocalDate.now(clock)}"
     * finding is exactly the class of bug this avoids.
     *
     * <p>The boundary is INCLUSIVE ({@code <=}, not {@code <}): a document
     * expiring at EXACTLY {@code now + within} is due within the window, not
     * one instant outside it.
     *
     * <p>Delegates to {@link AuthorizedQuery#findAll} under {@code
     * document.view} exactly like {@link #list}/{@link #forCase}, so the same
     * scope (departments/teams/uploader) and audience (targeting/sharing)
     * narrowing already proven for those two methods applies here for free --
     * this method adds no scope or audience logic of its own, only the
     * expiry+not-retired predicate. {@link #notRetired()} is ANDed in exactly
     * as it is for {@link #list}, since a retired document's own expiry date
     * is no longer meaningful (spec's own scope table, and this method's own
     * javadoc above).
     *
     * <p>Written so Postgres can use {@code document_tenant_expiry_idx}
     * ({@code V23__document.sql}: {@code ON document (tenant_id, expires_at)
     * WHERE expires_at IS NOT NULL AND status = 'ACTIVE'}) -- the predicate
     * filters on {@code expires_at IS NOT NULL} and (via {@link #notRetired()})
     * {@code status <> 'RETIRED'} rather than relying on Java-side filtering,
     * the same column shape that partial index was built for.
     */
    @RequirePermission(PermissionKeys.DOCUMENT_VIEW)
    @Transactional(readOnly = true)
    public Page<DocumentView> expiring(Duration within, Pageable pageable) {
        Instant cutoff = Instant.now(clock).plus(within);
        Specification<Document> expiringSoon = (root, query, cb) -> cb.and(
                cb.isNotNull(root.get("expiresAt")),
                cb.lessThanOrEqualTo(root.get("expiresAt"), cutoff));
        return authorizedQuery.findAll(documents, Document.class, PermissionKeys.DOCUMENT_VIEW,
                        expiringSoon.and(notRetired()), pageable)
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
        return authorizedQuery.findAll(documents, Document.class, PermissionKeys.DOCUMENT_VIEW,
                        homeOrLinked.and(notRetired()), pageable)
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
     * case lookup runs, rather than relying on that accident. {@code
     * addVersion} does NOT need this guard: its {@code caseId} comes from an
     * already-audience-filtered {@link Document}, never from the caller
     * directly.
     *
     * <p><b>Task 26 update:</b> the real portal upload path now exists --
     * {@link #uploadFromPortal}, reached through {@code
     * PortalDocumentController} and {@link PortalCaseAccess}, which does the
     * genuine narrowing this method deliberately never attempts. This method
     * itself is unchanged and still refuses every portal actor outright:
     * spec §8 never routes a portal caller through THIS exact signature
     * (with a raw, caller-supplied {@code caseId}) either way.
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
        UUID resolvedTargetDepartmentId = orgUnits.resolveDepartment(request.targetDepartmentId());
        UUID resolvedOwnerContactId = resolveOwnerContact(request.ownerContactId(), c);

        return persistNewDocument(c, actor, request, resolvedTargetDepartmentId,
                request.targetContactLabel(), resolvedOwnerContactId, stored, sizeBytes);
    }

    /**
     * Task 26: the real portal upload endpoint that finally replaces
     * {@link #upload}'s own blanket portal refusal with genuine narrowing --
     * see that method's own javadoc for the case-existence-oracle this exists
     * to close.
     *
     * <p>{@code c} arrives ALREADY resolved and ALREADY validated -- by
     * {@link PortalCaseAccess#resolveForContact}, called by {@code
     * PortalDocumentController} before this method is ever invoked -- never a
     * raw {@code caseId} this method resolves itself. There is nothing left
     * here to bypass: unlike {@link #upload}, this method never calls {@link
     * AuthorizedQuery#getById} on {@code Case} at all, because for a portal
     * actor there is no scope predicate that call could apply (the same
     * reasoning {@link PortalCaseAccess}'s own javadoc gives in full).
     *
     * <p><b>Self-defending, matching {@link #upload}'s own precedent -- fixed
     * in a review round, not part of Task 26's original shape.</b> This
     * method used to trust {@code c} and {@code actingContactId} completely,
     * with all three of the following guarantees living ENTIRELY in {@code
     * PortalDocumentController}: that the caller is actually a PORTAL actor,
     * that {@code actingContactId} genuinely names the authenticated
     * principal's own linked contact (never one a caller could simply pass
     * in), and that the case and contact actually share a customer. That
     * inverted {@link #upload}'s own convention (refuses {@code
     * UserType.PORTAL} INSIDE the service, not the controller) and repeated
     * the exact "gated write path trusting caller-supplied ids" shape
     * CLAUDE.md names as the cause of three prior sub-project-1 escalations
     * -- a future caller of this method that skipped or got the controller's
     * own resolution wrong would have had nothing here to catch it. Now this
     * method re-derives all three facts itself, before touching the upload
     * stream at all: (1) the current actor is genuinely {@code
     * UserType.PORTAL}; (2) re-resolving the acting contact through {@link
     * PortalContactDirectory#findActiveContactForUser} off the CURRENT
     * principal's own user id -- never trusting the parameter alone -- and
     * confirming its {@code id()} equals {@code actingContactId}, closing
     * "caller could pass anyone's contact id"; and (3) re-confirming {@code
     * c.getCustomerId()} equals that resolved contact's own {@code
     * customerId()}, defense in depth on top of {@link PortalCaseAccess}'s
     * own check, so this method stays safe even if some future caller skips
     * that step. All three refuse with the same {@link NoSuchElementException}
     * ("Not found") this method's own pre-existing failure shape already
     * uses -- indistinguishable from any other reason this call 404s.
     * {@code PortalDocumentController}'s own resolution is NOT redundant with
     * this -- it is what determines WHICH case and contact in the first
     * place; this is re-verifying the security-relevant facts before writing,
     * the same "confirm, don't just consume" shape {@link #resolveOwnerContact}
     * already uses for a caller-supplied contact id on the internal path.
     *
     * <p>{@code actingContactId} is forced onto {@link Document#ownerContactId}
     * UNCONDITIONALLY, regardless of {@code visibilityTier} -- never read from
     * {@code request.ownerContactId()} (which is always null anyway, since
     * {@link PortalCreateDocumentRequest} carries no such field for a caller
     * to even populate). {@code document_owner_ck} only requires a non-null
     * owner when the tier IS {@code CONTACT_ONLY}; it does not forbid one
     * otherwise, so recording the acting contact as owner on every portal
     * upload -- COMPANY_SHARED and SENSITIVE included -- is always a safe,
     * meaningful "who uploaded this" fact, not just a CONTACT_ONLY-only
     * concern.
     *
     * <p><b>Deliberately does NOT call {@link #applyWriteScope} at all</b> --
     * not an oversight, a considered ruling. {@code write_scope}
     * (ANY/DEPARTMENT/TEAM/OWNER_ONLY) narrows which INTERNAL staff may write
     * during a stage, checked against {@code ctx.userId()}/{@code teamIds()}/
     * {@code departmentId()} -- none of which has any meaningful mapping to a
     * portal contact. Applying it here would unpredictably block a customer
     * from uploading a document they were explicitly asked for, based on an
     * internal-only collaboration restriction that was never designed with an
     * external party in mind. Skipping it also sidesteps a SECOND portal
     * oracle: {@link #applyWriteScope}'s own {@link Stage} lookup is gated
     * {@code WORKFLOW_VIEW}, a permission portal actors never hold either, so
     * calling it here would 404 every portal upload against a case that has
     * entered a stage at all -- an accidental, permission-shaped refusal, not
     * a deliberate one. Portal's own narrowing is a complete, independent
     * security model for this audience: an ACTIVE contact resolved through
     * {@code authz.PortalContactDirectory}, combined with {@link
     * PortalCaseAccess}'s explicit customer match -- both already enforced
     * before this method is ever called. This is the first portal WRITE path
     * in the codebase; a future one should reach the same conclusion
     * deliberately, not copy this method without re-deriving it.
     */
    @RequirePermission(PermissionKeys.DOCUMENT_UPLOAD)
    @Transactional
    public DocumentView uploadFromPortal(Case c, UUID actingContactId, CreateDocumentRequest request,
                                         InputStream content, long sizeBytes, String declaredContentType) {
        var ctx = contextProvider.current();
        if (ctx.userType() != UserType.PORTAL) {
            throw new NoSuchElementException("Not found");
        }
        PortalContactFacts contact = portalContacts.findActiveContactForUser(ctx.userId())
                .orElseThrow(() -> new NoSuchElementException("Not found"));
        if (!contact.id().equals(actingContactId)) {
            throw new NoSuchElementException("Not found");
        }
        if (!c.getCustomerId().equals(contact.customerId())) {
            throw new NoSuchElementException("Not found");
        }

        UUID actor = ctx.userId();
        StoredContent stored = captureContent(request.category(), content, sizeBytes);

        // No targeting field a portal caller could have supplied in the first
        // place (PortalCreateDocumentRequest carries neither), and
        // actingContactId -- never request.ownerContactId() -- is always the
        // owner. See this method's own javadoc.
        return persistNewDocument(c, actor, request, null, null, actingContactId, stored, sizeBytes);
    }

    /**
     * The row-writing tail shared by {@link #upload} and {@link
     * #uploadFromPortal} -- everything after case resolution/write-scope and
     * id-resolution (which differ enough between the two callers that folding
     * them in here would obscure rather than clarify), so the two write paths
     * cannot silently drift apart on how a {@link Document} and its first
     * {@link DocumentVersion} are actually constructed and persisted.
     * {@code targetDepartmentId}/{@code targetContactLabel}/{@code
     * ownerContactId} are passed in already resolved -- this method resolves
     * nothing itself.
     */
    private DocumentView persistNewDocument(Case c, UUID uploadedBy, CreateDocumentRequest request,
                                            UUID resolvedTargetDepartmentId, String targetContactLabel,
                                            UUID resolvedOwnerContactId, StoredContent stored, long sizeBytes) {
        Document d = new Document();
        d.setId(Uuid7.generate());
        d.setTenantId(c.getTenantId());
        d.setCaseId(c.getId());
        d.setCustomerId(c.getCustomerId());
        d.setName(request.name());
        d.setCategory(request.category());
        d.setVisibilityTier(request.visibilityTier());
        d.setTargetDepartmentId(resolvedTargetDepartmentId);
        d.setTargetContactLabel(targetContactLabel);
        d.setOwnerContactId(resolvedOwnerContactId);
        d.setExpiresAt(request.expiresAt());
        d.setStatus(DocumentStatus.ACTIVE);
        d.setUploadedBy(uploadedBy);
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
                ReviewStatus.PENDING, uploadedBy, Instant.now(clock));
        versions.saveAndFlush(v);

        d.setCurrentVersionId(v.getId());
        d = documents.saveAndFlush(d);

        // Task 29: recorded against "onboarding_case"/c.getId() -- NOT
        // "document"/d.getId() -- so this surfaces on the case's own Activity
        // Timeline (journey.TimelineService.forCase resolves an exact
        // (resourceType, resourceId) match). See AuditActions' own comment
        // above DOCUMENT_UPLOADED for the full reasoning. No cause-before-
        // effect ordering concern here: persistNewDocument calls nothing on
        // RequirementService/CaseEngine itself.
        audit.record(AuditActions.DOCUMENT_UPLOADED, "onboarding_case", c.getId(),
                "Uploaded document " + d.getName(),
                Map.of("documentId", d.getId().toString(), "category", d.getCategory().name()));

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

        // Task 29: same "onboarding_case"/case-id resourceType as document.uploaded --
        // see that call site's own comment. resourceId is the DOCUMENT's id
        // (not the version id), keeping every document.* action's OWN
        // identifying payload field ("documentId") consistent, even though the
        // event itself is filed against the case.
        audit.record(AuditActions.DOCUMENT_VERSION_ADDED, "onboarding_case", c.getId(),
                "Added version " + nextVersionNo + " to document " + d.getName(),
                Map.of("documentId", d.getId().toString(), "versionNo", nextVersionNo));

        return toVersionView(v);
    }

    /**
     * Task 17: the metadata-update and retargeting path, gated
     * {@code document.manage} -- the ONE permission {@code
     * scoping.DocumentAudienceFilter} deliberately does NOT narrow (see its
     * own javadoc, and design spec Sec 6.4). That means the {@link
     * AuthorizedQuery#getById} call below loads {@code id} regardless of
     * its current targeting, precisely so a document targeted at a
     * department that has since emptied out stays recoverable rather than
     * permanently stuck -- the record-level DEPARTMENT/TEAM scope a
     * {@code document.manage} holder is granted at still narrows through
     * {@code scoping.DocumentDescriptor}, which resolves off the document's
     * CASE ownership, never off the document's own target column, so it
     * never re-imposes the same narrowing the audience filter just waived.
     *
     * {@link StageWriteScopeGuard} still applies on top, exactly as it does
     * for {@link #upload}/{@link #addVersion} (CLAUDE.md: "a new mutation
     * against a case's stage calls through it rather than re-deriving the
     * check") -- retargeting is still a write against the case's document,
     * and an {@code OWNER_ONLY} stage narrows it the same way regardless of
     * which permission gates the call.
     *
     * PATCH semantics: only a supplied (non-null) field on {@code request}
     * changes anything -- see {@link PatchDocumentRequest}'s own javadoc for
     * why fields cannot be explicitly cleared back to null through this
     * method. {@code targetDepartmentId}, when supplied, is resolved through
     * {@link OrgUnitResolver} exactly like {@link CreateDocumentRequest}'s
     * own field.
     *
     * Retargeting -- {@code targetDepartmentId} or {@code targetContactLabel}
     * actually CHANGING value, not merely being resupplied with the value
     * already on the row -- is recorded as its own action,
     * {@link AuditActions#DOCUMENT_RETARGETED}, never folded into a generic
     * "document updated" event (there is none): the same "retirement gets
     * its own action" shape CLAUDE.md already states for
     * {@code contact.deactivated}, because this is precisely the action a
     * mis-targeting recovery needs to be able to find in the log on its own.
     * A pure rename/recategorise records nothing here -- Task 29 is what
     * eventually adds a general document.* audit family; this task adds
     * only the one constant its own implementation needs.
     */
    @RequirePermission(PermissionKeys.DOCUMENT_MANAGE)
    @Transactional
    public DocumentView patch(UUID id, PatchDocumentRequest request) {
        Document d = authorizedQuery.getById(documents, Document.class, PermissionKeys.DOCUMENT_MANAGE, id);
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.DOCUMENT_MANAGE, d.getCaseId());
        applyWriteScope(c);

        boolean retargeted = false;
        // Captured before either targeting field is written, so the audit
        // payload below can report what actually changed -- see this
        // method's own javadoc and CLAUDE.md's "an audit trail the
        // application can rewrite is not evidence": once d.setTargetXxx(...)
        // runs, the pre-patch value is gone for good.
        UUID fromDepartmentId = d.getTargetDepartmentId();
        String fromContactLabel = d.getTargetContactLabel();

        if (request.name() != null) d.setName(request.name());
        if (request.category() != null) d.setCategory(request.category());

        if (request.targetDepartmentId() != null) {
            UUID resolved = orgUnits.resolveDepartment(request.targetDepartmentId());
            if (!resolved.equals(d.getTargetDepartmentId())) {
                d.setTargetDepartmentId(resolved);
                retargeted = true;
            }
        }
        if (request.targetContactLabel() != null
                && !request.targetContactLabel().equals(d.getTargetContactLabel())) {
            d.setTargetContactLabel(request.targetContactLabel());
            retargeted = true;
        }

        d = documents.saveAndFlush(d);

        if (retargeted) {
            // A plain Map.of(...) throws NPE the moment any one of these four
            // values is null (an untargeted document has a null
            // targetDepartmentId/targetContactLabel), so this builds a mutable
            // map and null-safe-converts each value instead -- AuditRecorder's
            // payload is serialized through Jackson, which writes a null map
            // value as JSON null with no special handling required.
            Map<String, Object> payload = new HashMap<>();
            payload.put("caseId", d.getCaseId().toString());
            payload.put("fromDepartmentId", fromDepartmentId == null ? null : fromDepartmentId.toString());
            payload.put("toDepartmentId", d.getTargetDepartmentId() == null ? null : d.getTargetDepartmentId().toString());
            payload.put("fromContactLabel", fromContactLabel);
            payload.put("toContactLabel", d.getTargetContactLabel());
            audit.record(AuditActions.DOCUMENT_RETARGETED, "document", d.getId(),
                    "Retargeted document " + d.getName(),
                    payload);
        }

        return toView(d);
    }

    /**
     * Task 18 (design spec 5.5): what retiring a document revokes, all four
     * parts in one transaction --
     * <ol>
     *   <li>Revokes every LIVE {@link DocumentShare} for it. A share is
     *       access; ending the record ends the access.</li>
     *   <li>Revokes every LIVE {@link DocumentCaseLink} for it.</li>
     *   <li>Reopens any requirement it satisfied, through the gated
     *       {@code journey.RequirementService.reopen} -- never a direct write
     *       to requirement state, and no new caller of
     *       {@code CaseEngine.reconcile} (that method's own job). Checked
     *       FIRST via {@link RequirementRepository#satisfiedBy}, and
     *       {@code reopen} is called only when that check is non-empty -- so
     *       a {@code document.manage} holder retiring a document that never
     *       satisfied anything never needs {@code milestone.complete} at
     *       all. See {@code RequirementService.reopen}'s own javadoc for why
     *       this is the mirror image of sub-project 3's task-cancellation
     *       rule, not a contradiction of it.</li>
     *   <li>Leaves the bytes. Business records are never deleted (spec 7.1)
     *       -- there is no {@code BlobStore.delete} to call in the first
     *       place.</li>
     * </ol>
     *
     * {@code reason} is validated (a blank reason is refused, the same
     * "no way to waive/cancel silently" shape {@code RequirementService.waive}
     * and {@code task.TaskService.changeStatus}'s cancellation branch both
     * already use) and, as of Task 29, recorded as {@link AuditActions#DOCUMENT_RETIRED}'s
     * own payload -- see this method's body for the exact placement relative
     * to the share/link revocation and the conditional reopen call below.
     *
     * {@link StageWriteScopeGuard} still applies on top, exactly as it does
     * for {@link #upload}/{@link #addVersion}/{@link #patch}.
     *
     * <p><b>Interaction with an ON_HOLD case, deliberately left as-is:</b> when
     * the document being retired satisfied a requirement of a case that is
     * currently {@code ON_HOLD}, the call into {@code journey.RequirementService
     * .reopen} above throws {@code journey.CaseOnHoldException} -- and because
     * that call runs inside this method's own {@code @Transactional} boundary
     * (the same transaction, not a nested one), the exception rolls back the
     * ENTIRE {@code retire} call, including the share and link revocations that
     * would otherwise already have succeeded. Concretely: retiring a document
     * that satisfied a requirement of a currently-held case fails completely --
     * nothing is revoked, the document stays ACTIVE -- until the hold clears,
     * even though revoking access to a wrongly-uploaded document is arguably
     * most urgent exactly when something about the case is already wrong. This
     * is deliberately NOT special-cased here: every other {@code
     * RequirementService} mutation already refuses outright during a hold, and
     * carving out an exception so retire's share/link revocations could survive
     * while reopen still refuses would be a bigger change to hold semantics
     * than this method should make unilaterally. A future decision to make
     * retirement partially succeed under a hold (share/link revocation first,
     * reopen deferred) needs to be made explicitly, with its own test, not as
     * a side effect of this note.
     */
    @RequirePermission(PermissionKeys.DOCUMENT_MANAGE)
    @Transactional
    public DocumentView retire(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A retirement reason is required");
        }

        Document d = authorizedQuery.getById(documents, Document.class, PermissionKeys.DOCUMENT_MANAGE, id);
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.DOCUMENT_MANAGE, d.getCaseId());
        applyWriteScope(c);

        Instant now = Instant.now(clock);
        for (DocumentShare share : shares.liveSharesOf(d.getId())) {
            share.setRevokedAt(now);
            shares.save(share);
        }
        for (DocumentCaseLink link : caseLinks.liveLinksOf(d.getId())) {
            link.setRevokedAt(now);
            caseLinks.save(link);
        }

        // Task 29, this method's own javadoc's own forward reference, finally
        // closed: recorded AFTER the share/link revocation saves above but
        // BEFORE the conditional reopen call below -- cause before effect,
        // since reopen may itself complete further audited work (case.completed
        // moving backward, in principle). "onboarding_case"/c.getId(), the same
        // resourceType every other timeline-visible document.* action uses --
        // see AuditActions' own comment.
        audit.record(AuditActions.DOCUMENT_RETIRED, "onboarding_case", c.getId(),
                "Retired document " + d.getName() + ": " + reason,
                Map.of("documentId", d.getId().toString(), "reason", reason));

        // Only calls the gated reopen when there is actually something to
        // reopen -- see this method's own javadoc, point 3.
        if (!requirementRepository.satisfiedBy(d.getId(), SATISFIED_REF_TYPE).isEmpty()) {
            requirementService.reopen(d.getId(), SATISFIED_REF_TYPE);
        }

        d.setStatus(DocumentStatus.RETIRED);
        d = documents.saveAndFlush(d);

        return toView(d);
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
     * Closes the confused-deputy gap {@link CreateDocumentRequest#ownerContactId}'s
     * own (now-stale) javadoc used to document as a deliberate simplification:
     * {@code owner_contact_id} has a foreign key to {@code customer_contact(id)},
     * which only proves the contact exists SOMEWHERE in the tenant, never that
     * it belongs to the same customer as the document being uploaded. Note this
     * is NOT the cross-customer disclosure it might look like at first glance --
     * {@code scoping.DocumentAudienceFilter#portalAudience}'s {@code atMyCustomer}
     * conjunct is ANDed with the CONTACT_ONLY tier check, so a customer-B contact
     * can never pass that branch for a customer-A document regardless of what
     * {@code owner_contact_id} points at; that read-side check was independently
     * sufficient against disclosure even before this fix. What an unresolved id
     * DID leave open: (1) a bogus or cross-tenant id surfaced a raw
     * {@code DataIntegrityViolationException} instead of a clean 404, the same
     * existence-oracle class {@link OrgUnitResolver} already exists to close for
     * department/team ids; (2) a CONTACT_ONLY document could be silently created
     * with an owner at the WRONG customer, making it permanently unreachable by
     * anyone -- no contact at the right customer matches {@code owner_contact_id},
     * and no contact at the wrong customer passes {@code atMyCustomer}; and (3)
     * defense-in-depth -- a future write path, or a future change to the filter
     * above, that ever consults {@code owner_contact_id} without also checking
     * {@code atMyCustomer} would turn an unvalidated cross-customer id into a
     * real disclosure, and validating it here closes that class of risk before
     * it can matter rather than relying solely on the read side.
     *
     * <p>Mirrors {@link DocumentSharingService#resolveContact} exactly:
     * {@code null} passes straight through (an untargeted document has no
     * owner contact at all, the same "nullable stays nullable" contract
     * {@link OrgUnitResolver#resolveDepartment} already carries for
     * {@code targetDepartmentId}), a non-null id is resolved through
     * {@link AuthorizedQuery} under {@code contact.view} -- composing that
     * READ permission with this method's own {@code document.upload} WRITE
     * gate, the same shape used throughout this module -- and a resolved
     * contact belonging to a different customer than {@code c} is refused
     * with {@link IllegalArgumentException} (400, never the 404 an absent or
     * out-of-scope contact id already gets from {@code AuthorizedQuery
     * #getById} on its own).
     */
    private UUID resolveOwnerContact(UUID ownerContactId, Case c) {
        if (ownerContactId == null) return null;
        CustomerContact contact = authorizedQuery.getById(
                contacts, CustomerContact.class, PermissionKeys.CONTACT_VIEW, ownerContactId);
        if (!contact.getCustomerId().equals(c.getCustomerId())) {
            throw new IllegalArgumentException(
                    "Contact " + contact.getId() + " belongs to a different customer than case " + c.getId());
        }
        return contact.getId();
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

    /** RETIRED excluded from every listing -- {@link #list}/{@link #forCase}'s own javadoc. */
    private static Specification<Document> notRetired() {
        return (root, query, cb) -> cb.notEqual(root.get("status"), DocumentStatus.RETIRED);
    }

    /**
     * ANDs an optional {@code visibilityTier} equality onto {@code base} --
     * {@code null} leaves {@code base} untouched, matching {@link #list}'s
     * pre-existing "no filter" contract. Shared by {@link #list} and
     * {@link #visibilitySummary} so the two can never silently define "the
     * current filter" differently from one another.
     */
    private static Specification<Document> withTier(Specification<Document> base, VisibilityTier visibilityTier) {
        if (visibilityTier == null) return base;
        return base.and((root, query, cb) -> cb.equal(root.get("visibilityTier"), visibilityTier));
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
