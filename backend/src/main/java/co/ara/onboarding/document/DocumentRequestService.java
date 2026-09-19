package co.ara.onboarding.document;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.customer.CustomerContact;
import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.journey.StageWriteScopeGuard;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Task 23 (design spec 4.5/5.2): the ad-hoc half of {@code document_request} --
 * a caller-initiated request for a customer contact to supply a document, with
 * a permanently null {@code requirementId}. The requirement-instantiated half
 * ({@code DocumentInstantiation}, modelled on {@code task.TaskInstantiation})
 * is Task 24's own job and writes {@code document_request} rows of its own,
 * never through this class's {@link #create}.
 *
 * <p>Task 25 adds {@link #fulfil}, moving a request OPEN -&gt; FULFILLED
 * (never a DELETE) and, when the request is linked to a requirement and does
 * not require review, satisfying that requirement through the existing
 * gated {@code journey.RequirementService#satisfy} -- see that method's own
 * javadoc for the exact branching.
 *
 * <p>Every id this class receives from a URL or a request body -- {@code
 * caseId}, {@code requestId}, {@code requestedOfContactId}, and (as of Task
 * 25) {@code documentId} -- is resolved through {@link AuthorizedQuery}
 * before anything is written, the same write-path invariant {@code
 * task.TaskService}'s and {@link DocumentSharingService}'s own class
 * javadocs name and CLAUDE.md states outright.
 */
@Service
public class DocumentRequestService {

    private final DocumentRequestRepository requests;
    private final DocumentRepository documents;
    private final CaseRepository cases;
    private final StageRepository stages;
    private final CustomerContactRepository contacts;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final StageWriteScopeGuard writeScope;
    private final RequirementService requirementService;
    private final Clock clock;
    private final AuditRecorder audit;

    public DocumentRequestService(DocumentRequestRepository requests, DocumentRepository documents,
                                  CaseRepository cases, StageRepository stages,
                                  CustomerContactRepository contacts, AuthorizedQuery authorizedQuery,
                                  AuthContextProvider contextProvider, StageWriteScopeGuard writeScope,
                                  RequirementService requirementService, Clock clock, AuditRecorder audit) {
        this.requests = requests;
        this.documents = documents;
        this.cases = cases;
        this.stages = stages;
        this.contacts = contacts;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.writeScope = writeScope;
        this.requirementService = requirementService;
        this.clock = clock;
        this.audit = audit;
    }

    /**
     * Creates an ad-hoc request (design spec 5.2: "The case header's 'Request
     * document' creates one with a null {@code requirementId}"). {@code
     * caseId} is resolved through {@link AuthorizedQuery} under {@code
     * document.request} first, and {@link StageWriteScopeGuard} narrows on
     * top exactly as every other write in this module does.
     *
     * <p>{@code request.requestedOfContactId()}, when non-null, is resolved
     * through {@link #resolveContact} -- the identical "resolve under a READ
     * permission, then refuse a cross-customer mismatch as a 400" shape
     * {@link DocumentSharingService#resolveContact} already establishes for
     * a share principal.
     *
     * <p>{@code tenantId} is always copied from the resolved case, never
     * accepted from a caller -- there is no field on {@link
     * CreateDocumentRequestRequest} that could even carry one.
     */
    @RequirePermission(PermissionKeys.DOCUMENT_REQUEST)
    @Transactional
    public DocumentRequestView create(UUID caseId, CreateDocumentRequestRequest request) {
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.DOCUMENT_REQUEST, caseId);
        applyWriteScope(c);

        UUID resolvedContactId = request.requestedOfContactId() == null
                ? null
                : resolveContact(request.requestedOfContactId(), c);

        DocumentRequest dr = new DocumentRequest();
        dr.setId(Uuid7.generate());
        dr.setTenantId(c.getTenantId());
        dr.setCaseId(c.getId());
        // Ad-hoc, per this method's own contract -- CreateDocumentRequestRequest
        // has no requirementId field at all, so there is nothing to copy.
        dr.setRequirementId(null);
        dr.setRequestedOfContactId(resolvedContactId);
        dr.setCategory(request.category());
        dr.setDescription(request.description());
        dr.setDueAt(request.dueAt());
        dr.setRequiresReview(request.requiresReview());
        dr.setStatus(DocumentRequestStatus.OPEN);
        dr.setFulfilledDocumentId(null);
        dr.setRequestedBy(contextProvider.current().userId());
        dr.setRequestedAt(Instant.now(clock));

        dr = requests.saveAndFlush(dr);

        // Task 29: "onboarding_case"/c.getId() -- same resourceType every
        // other timeline-visible document.* action uses (AuditActions' own
        // comment above DOCUMENT_UPLOADED). DocumentInstantiation's own
        // requirement-instantiated document_request rows deliberately record
        // nothing here (see that class's own javadoc) -- this fires only for
        // THIS method's ad-hoc path.
        audit.record(AuditActions.DOCUMENT_REQUESTED, "onboarding_case", c.getId(),
                "Requested a document on case " + c.getId(),
                Map.of("requestId", dr.getId().toString(), "category", dr.getCategory().name()));

        return toView(dr);
    }

    /**
     * Task 36: lists a case's document requests -- the one gap that made this
     * whole module's own e2e spec impossible to write, since {@code
     * DocumentInstantiation.instantiateForCase}'s auto-instantiated rows are
     * otherwise undiscoverable over HTTP (it runs fire-and-forget inside
     * {@code CaseService.create}'s own transaction and returns nothing to any
     * caller). Resolves {@code caseId} through {@link AuthorizedQuery} FIRST,
     * under {@code document.request} itself, the same "confirm the parent is
     * visible before listing its children" idiom {@link DocumentService#forCase}
     * and {@code task.TaskService.forCase} already use -- so an out-of-scope
     * {@code caseId} is a 404 here too, never a silently empty page.
     *
     * <p>Deliberately does NOT call {@link DocumentRequestRepository#findByCaseId}
     * -- that finder is a narrowly-scoped exclusion for {@code
     * DocumentInstantiation} alone (safe there only because it runs on a
     * caseId {@code CaseService.create} just created and fully controls).
     * This method takes {@code caseId} from a URL path instead, so it reads
     * through {@link AuthorizedQuery#findAll} under the same {@code
     * document.request} permission, exactly the shape {@code
     * AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly}
     * exists to enforce.
     */
    @RequirePermission(PermissionKeys.DOCUMENT_REQUEST)
    @Transactional(readOnly = true)
    public Page<DocumentRequestView> forCase(UUID caseId, Pageable pageable) {
        authorizedQuery.getById(cases, Case.class, PermissionKeys.DOCUMENT_REQUEST, caseId);

        Specification<DocumentRequest> onCase = (root, query, cb) -> cb.equal(root.get("caseId"), caseId);
        return authorizedQuery.findAll(requests, DocumentRequest.class, PermissionKeys.DOCUMENT_REQUEST,
                        onCase, pageable)
                .map(DocumentRequestService::toView);
    }

    /**
     * Withdraws a request -- a column, never a DELETE (design spec 4.5,
     * {@code document_request} carries no {@code GRANT DELETE}). {@code
     * reason} is validated non-blank but deliberately NOT persisted anywhere;
     * see {@link WithdrawDocumentRequestRequest}'s own javadoc for why that is
     * the same precedent as {@link DocumentService#retire}'s own {@code
     * reason} parameter, not an oversight.
     *
     * <p>Idempotent on an already-{@code WITHDRAWN} request -- left exactly as
     * it was, never re-processed -- the identical shape {@link
     * DocumentSharingService#revokeShare} already establishes for a share.
     * Refused with {@link IllegalStateException} (409) when the request is
     * already {@code FULFILLED}: a terminal state once reached, the same
     * "any open state may end, but a completed one cannot move again" shape
     * {@code task.TaskService}'s own transition map already enforces for
     * {@code TaskStatus.COMPLETED}.
     *
     * <p><b>Design spec 5.2's own invariant, load-bearing here</b>: "A
     * WITHDRAWN request never satisfies its requirement -- the same rule as
     * sub-project 3's 'a cancelled task never satisfies or waives its
     * requirement', and for the same reason." This method calls nothing on
     * {@code journey.RequirementService} and never reaches {@code
     * CaseEngine.reconcile} -- it is a single-column status write and nothing
     * else, structurally incapable of satisfying or reopening anything.
     *
     * <p>{@link StageWriteScopeGuard} narrows on top, the same pattern {@link
     * #create} and every other write in this module already use.
     */
    @RequirePermission(PermissionKeys.DOCUMENT_REQUEST)
    @Transactional
    public DocumentRequestView withdraw(UUID requestId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A withdrawal reason is required");
        }

        DocumentRequest dr = authorizedQuery.getById(
                requests, DocumentRequest.class, PermissionKeys.DOCUMENT_REQUEST, requestId);
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.DOCUMENT_REQUEST, dr.getCaseId());
        applyWriteScope(c);

        if (dr.getStatus() == DocumentRequestStatus.FULFILLED) {
            throw new IllegalStateException(
                    "Document request " + dr.getId() + " is already fulfilled and cannot be withdrawn");
        }

        if (dr.getStatus() != DocumentRequestStatus.WITHDRAWN) {
            dr.setStatus(DocumentRequestStatus.WITHDRAWN);
            dr = requests.saveAndFlush(dr);

            // Task 29: same "onboarding_case" resourceType as document.requested
            // above. Only on the branch that actually transitions status --
            // the already-WITHDRAWN idempotent no-op records nothing, the
            // same "nothing changed" reasoning DocumentSharingService.share/
            // revokeShare already use.
            audit.record(AuditActions.DOCUMENT_REQUEST_WITHDRAWN, "onboarding_case", c.getId(),
                    "Withdrew document request " + dr.getId() + ": " + reason,
                    Map.of("requestId", dr.getId().toString(), "reason", reason));
        }
        return toView(dr);
    }

    /**
     * Fulfils an open request with an existing document (Task 25; design
     * spec 5.2/5.3). {@code documentId} is resolved through {@link
     * AuthorizedQuery} under {@code document.view} -- composed with this
     * method's own {@code document.request} write gate, the identical
     * "resolve under a READ permission, then use it in a WRITE method" shape
     * {@link DocumentSharingService#resolveContact} already establishes,
     * here because fulfilling a request needs the ability to actually SEE
     * the document being offered as fulfilment, not merely to write a
     * request.
     *
     * <p>Refused with {@link IllegalArgumentException} (400) when the
     * resolved document's own {@code caseId} does not match the resolved
     * request's own -- the same confused-deputy cross-reference shape
     * {@link #resolveContact} and {@link DocumentSharingService#link} already
     * use, between two already-resolved records, never the acting actor's
     * own scope. An exact match only: whether a document merely LINKED into
     * the request's case (rather than being its home case, {@link
     * DocumentSharingService#link}'s own cross-journey links) should also
     * count is a real, unresolved edge case, deliberately not solved here.
     *
     * <p>Also refused with {@link IllegalArgumentException} (400) when the
     * resolved document's own {@code status} is {@link DocumentStatus#RETIRED}
     * -- the same "document is unsuitable for this fulfilment" shape as the
     * cross-case check just above, read consistently as the same exception
     * and status. {@link DocumentService#get} deliberately keeps a retired
     * document reachable by id (a status change, not an existence change),
     * so {@code AuthorizedQuery} resolving it above succeeds fine; without
     * this check a requirement could be satisfied against a document nobody
     * can ever open again, with nothing left to reopen it, since {@link
     * DocumentService#retire} already reopened whatever it satisfied at the
     * time it was retired -- exactly the silent-false-positive shape {@code
     * journey.RequirementService#reopen}'s own javadoc names as the reason
     * retirement reopens requirements in the first place.
     *
     * <p>Refused with {@link IllegalStateException} (409) when the request's
     * own {@code status} is not {@link DocumentRequestStatus#OPEN} -- the
     * mirror image of {@link #withdraw}'s own already-FULFILLED guard: here
     * an already-{@code FULFILLED} or already-{@code WITHDRAWN} request is
     * equally terminal.
     *
     * <p>{@code status}/{@code fulfilledDocumentId} are written
     * unconditionally once past both guards above. Satisfying the linked
     * requirement is NOT unconditional (design spec 5.3): only when {@code
     * requirementId} is non-null AND {@code requiresReview} is false does
     * this call {@code journey.RequirementService#satisfy} -- exactly once,
     * as the very last step, and nothing else here locks, reconciles, or
     * recomputes progress; {@code satisfy} already does all three under its
     * own gate and {@code CaseRepository.lockById}'s row lock. When {@code
     * requiresReview} is true, the request still moves to FULFILLED but the
     * requirement stays exactly as it was -- a future review-approval step's
     * own job to satisfy, not this method's. When {@code requirementId} is
     * null (an ad-hoc request), there is nothing to satisfy regardless of
     * {@code requiresReview}, and {@code satisfy} is never called.
     *
     * <p><b>Task 29 closes the gap this javadoc used to flag</b>: {@link
     * AuditActions#DOCUMENT_REQUEST_FULFILLED} (added specifically because
     * this method's own silence here was a real, asymmetric gap -- not in
     * Task 29's original nine-key brief, see that constant's own comment) is
     * recorded UNCONDITIONALLY, covering all three branches this method can
     * take (ad-hoc, requires-review, and satisfy-eligible alike) -- never
     * only the one branch that happened to already get an audit trail for
     * free through {@code requirement.satisfied}. Recorded immediately after
     * {@code dr} is saved and BEFORE the conditional {@code satisfy} call
     * below, per {@code AuditRecorder}'s cause-before-effect ordering rule:
     * {@code satisfy} may itself trigger further recorded work
     * ({@code requirement.satisfied}, and in turn {@code milestone.completed}),
     * so this method's own record of the fulfilment must precede it.
     * "onboarding_case"/c.getId(), the same resourceType every other
     * timeline-visible document.* action uses.
     *
     * <p>{@link StageWriteScopeGuard} narrows on top, the same pattern
     * {@link #create} and {@link #withdraw} already use.
     */
    @RequirePermission(PermissionKeys.DOCUMENT_REQUEST)
    @Transactional
    public DocumentRequestView fulfil(UUID requestId, UUID documentId) {
        DocumentRequest dr = authorizedQuery.getById(
                requests, DocumentRequest.class, PermissionKeys.DOCUMENT_REQUEST, requestId);
        Document d = authorizedQuery.getById(
                documents, Document.class, PermissionKeys.DOCUMENT_VIEW, documentId);

        if (!d.getCaseId().equals(dr.getCaseId())) {
            throw new IllegalArgumentException(
                    "Document " + d.getId() + " belongs to a different case than document request " + dr.getId());
        }

        if (d.getStatus() == DocumentStatus.RETIRED) {
            throw new IllegalArgumentException(
                    "Document " + d.getId() + " is retired and cannot fulfil document request " + dr.getId());
        }

        if (dr.getStatus() != DocumentRequestStatus.OPEN) {
            throw new IllegalStateException(
                    "Document request " + dr.getId() + " is not open and cannot be fulfilled");
        }

        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.DOCUMENT_REQUEST, dr.getCaseId());
        applyWriteScope(c);

        dr.setStatus(DocumentRequestStatus.FULFILLED);
        dr.setFulfilledDocumentId(d.getId());
        dr = requests.saveAndFlush(dr);

        // Cause before effect (see this method's own javadoc): recorded
        // unconditionally, before the conditional satisfy call below, which
        // may itself trigger requirement.satisfied and milestone.completed.
        audit.record(AuditActions.DOCUMENT_REQUEST_FULFILLED, "onboarding_case", c.getId(),
                "Fulfilled document request " + dr.getId(),
                Map.of("requestId", dr.getId().toString(), "documentId", d.getId().toString()));

        if (dr.getRequirementId() != null && !dr.isRequiresReview()) {
            requirementService.satisfy(dr.getRequirementId(), d.getId(), DocumentService.SATISFIED_REF_TYPE);
        }

        return toView(dr);
    }

    /**
     * Task 19's own ruling, carried forward verbatim ({@link
     * DocumentSharingService#resolveContact}): a CONTACT that genuinely
     * exists and is genuinely visible to the actor, but belongs to a
     * DIFFERENT customer than the case being requested against, is refused as
     * an {@link IllegalArgumentException} (400) -- a cross-reference
     * validation between two already-resolved records, never the acting
     * actor's own scope, so deliberately NOT the 404 an out-of-scope or
     * nonexistent contact id already gets from {@link AuthorizedQuery#getById}
     * on its own.
     */
    private UUID resolveContact(UUID contactId, Case c) {
        CustomerContact contact = authorizedQuery.getById(
                contacts, CustomerContact.class, PermissionKeys.CONTACT_VIEW, contactId);
        if (!contact.getCustomerId().equals(c.getCustomerId())) {
            throw new IllegalArgumentException(
                    "Contact " + contact.getId() + " belongs to a different customer than case " + c.getId());
        }
        return contact.getId();
    }

    /** Same guard, same reasoning, as {@code DocumentService#applyWriteScope} -- see its own javadoc. */
    private void applyWriteScope(Case c) {
        if (c.getCurrentStageId() == null) return;
        Stage stage = authorizedQuery.getById(stages, Stage.class, PermissionKeys.WORKFLOW_VIEW, c.getCurrentStageId());
        writeScope.check(c, stage);
    }

    private static DocumentRequestView toView(DocumentRequest dr) {
        return new DocumentRequestView(dr.getId(), dr.getCaseId(), dr.getRequirementId(),
                dr.getRequestedOfContactId(), dr.getCategory(), dr.getDescription(), dr.getDueAt(),
                dr.isRequiresReview(), dr.getStatus(), dr.getFulfilledDocumentId(),
                dr.getRequestedBy(), dr.getRequestedAt());
    }
}
