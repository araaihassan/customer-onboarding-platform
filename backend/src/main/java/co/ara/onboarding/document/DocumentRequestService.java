package co.ara.onboarding.document;

import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.customer.CustomerContact;
import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.StageWriteScopeGuard;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Task 23 (design spec 4.5/5.2): the ad-hoc half of {@code document_request} --
 * a caller-initiated request for a customer contact to supply a document, with
 * a permanently null {@code requirementId}. The requirement-instantiated half
 * ({@code DocumentInstantiation}, modelled on {@code task.TaskInstantiation})
 * is Task 24's own job and writes {@code document_request} rows of its own,
 * never through this class's {@link #create}.
 *
 * <p>{@code fulfil} does not exist yet (Task 25) -- this class only ever moves
 * a request OPEN -&gt; WITHDRAWN, never OPEN -&gt; FULFILLED.
 *
 * <p>Every id this class receives from a URL or a request body -- {@code
 * caseId}, {@code requestId}, and {@code requestedOfContactId} itself -- is
 * resolved through {@link AuthorizedQuery} before anything is written, the
 * same write-path invariant {@code task.TaskService}'s and {@link
 * DocumentSharingService}'s own class javadocs name and CLAUDE.md states
 * outright.
 */
@Service
public class DocumentRequestService {

    private final DocumentRequestRepository requests;
    private final CaseRepository cases;
    private final StageRepository stages;
    private final CustomerContactRepository contacts;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final StageWriteScopeGuard writeScope;
    private final Clock clock;

    public DocumentRequestService(DocumentRequestRepository requests, CaseRepository cases, StageRepository stages,
                                  CustomerContactRepository contacts, AuthorizedQuery authorizedQuery,
                                  AuthContextProvider contextProvider, StageWriteScopeGuard writeScope, Clock clock) {
        this.requests = requests;
        this.cases = cases;
        this.stages = stages;
        this.contacts = contacts;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.writeScope = writeScope;
        this.clock = clock;
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
        return toView(dr);
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
