package co.ara.onboarding.document;

import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.Requirement;
import co.ara.onboarding.journey.RequirementRepository;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.journey.RequirementStatus;
import co.ara.onboarding.journey.StageWriteScopeGuard;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Task 27 (design spec 5.4): approve/reject a specific {@link DocumentVersion},
 * and the cross-case pending-review queue.
 *
 * <p>{@code documentId} is resolved through {@link AuthorizedQuery} under
 * {@code document.review} FIRST -- the same "confirm the parent is visible
 * before writing" idiom every other write in this module already uses -- and
 * is then refused with {@link IllegalArgumentException} (400) if its status
 * is {@link DocumentStatus#RETIRED}, the identical guard and exception/status
 * shape {@link DocumentRequestService#fulfil} already carries for the same
 * reason: {@link DocumentService#retire} reopens whatever the document
 * satisfied at retirement time, but a {@link DocumentRequest} it fulfilled
 * stays FULFILLED with {@code fulfilledDocumentId} still pointing at it, so
 * without this check a later APPROVE on any version of the now-unreachable
 * document would silently re-satisfy the requirement retirement already
 * reopened. Checked before resolving the specific version, so a retired
 * document's review is refused uniformly regardless of version number. The
 * specific version is then resolved by number through {@link
 * DocumentVersionRepository#versionAt}, a pre-authorized-id discovery finder
 * (see its own javadoc) rather than a fresh caller-supplied entity id.
 * {@link StageWriteScopeGuard} narrows on top exactly as it does for {@link
 * DocumentService#upload}/{@code addVersion}/{@code patch}/{@code retire}.
 *
 * <p><b>APPROVE</b> discovers every {@code FULFILLED} {@link DocumentRequest}
 * this document fulfilled ({@link DocumentRequestRepository#fulfilledBy}) and,
 * for each one that is requirement-instantiated ({@code requirementId != null}),
 * calls the existing gated {@code journey.RequirementService#satisfy} --
 * unless {@link RequirementRepository#byId} shows the requirement is ALREADY
 * {@code SATISFIED}, in which case the call is skipped entirely rather than
 * relying on {@code satisfy}'s own idempotent early-return. That early-return
 * runs AFTER {@code satisfy}'s own {@code milestone.complete} gate and its
 * {@code CaseOnHoldException} check, so calling it at all -- even for what
 * would end up a pure no-op -- demands a permission and a case state a no-op
 * has no real reason to require; skipping the call is what actually avoids
 * that friction, not the idempotency {@code satisfy} already had. Deliberately
 * does NOT filter by {@code requiresReview} first: a request whose {@code
 * requiresReview} was false (already satisfied back at {@code fulfil} time,
 * Task 25) is caught by the same already-SATISFIED skip above, so there is no
 * separate filter to keep in sync with {@code satisfy}'s own contract.
 *
 * <p><b>REJECT</b> mirrors {@link DocumentService#retire}'s own reopen
 * cascade exactly: {@link RequirementRepository#satisfiedBy} finds whatever
 * this document satisfied (there can, in principle, be more than one), and
 * {@code RequirementService#reopen} undoes it. A version that was never
 * satisfied in the first place naturally finds nothing and reopens nothing --
 * no special case needed.
 *
 * <p>Both branches compose {@code document.review} (this method's own gate)
 * with {@code milestone.complete} ({@code satisfy}/{@code reopen}'s own gate)
 * -- the identical two-permission composition {@code
 * DocumentService#retire}/{@code DocumentRequestService#fulfil} already
 * establish. Every seeded template holding {@code document.review} (Legal,
 * Finance, Compliance, Administrator -- {@code RoleTemplates}) also holds
 * {@code milestone.complete} at the SAME scope (all four at ALL), so there is
 * no equivalent here to Task 25's own Account-Manager/{@code document.request}
 * gap -- checked directly against {@code RoleTemplates} before writing this
 * class, not assumed.
 *
 * <p>Re-reviewing an already-decided version (approve-then-approve-again, or
 * approve-then-reject) is treated as ordinary idempotent re-application, the
 * same posture {@code RequirementService.satisfy}/{@code .reopen} both already
 * take, rather than refused outright: {@code reviewedBy}/{@code reviewedAt}/
 * {@code reviewNote} are simply overwritten with the latest decision, and the
 * requirement-side effect ({@code satisfy} or {@code reopen}) runs again,
 * itself a no-op when nothing has changed. There is no product requirement
 * anywhere (spec 5.4, QA) that a review decision be final, and refusing a
 * second look would make correcting a reviewer's own mis-click impossible
 * without an entirely new version.
 *
 * <p>No audit action is recorded here -- {@code document.reviewed} is one of
 * Task 29's own future {@code document.*} audit family, not this task's job
 * (the same "not yet audited, a later task's job" note {@link
 * DocumentService#retire}'s own javadoc already carries for {@code
 * document.retired}).
 */
@Service
public class DocumentReviewService {

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentRequestRepository requests;
    private final CaseRepository cases;
    private final StageRepository stages;
    private final RequirementRepository requirementRepository;
    private final RequirementService requirementService;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final StageWriteScopeGuard writeScope;
    private final Clock clock;

    public DocumentReviewService(DocumentRepository documents, DocumentVersionRepository versions,
                                 DocumentRequestRepository requests, CaseRepository cases, StageRepository stages,
                                 RequirementRepository requirementRepository, RequirementService requirementService,
                                 AuthorizedQuery authorizedQuery, AuthContextProvider contextProvider,
                                 StageWriteScopeGuard writeScope, Clock clock) {
        this.documents = documents;
        this.versions = versions;
        this.requests = requests;
        this.cases = cases;
        this.stages = stages;
        this.requirementRepository = requirementRepository;
        this.requirementService = requirementService;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.writeScope = writeScope;
        this.clock = clock;
    }

    @RequirePermission(PermissionKeys.DOCUMENT_REVIEW)
    @Transactional
    public DocumentVersionView review(UUID documentId, int versionNo, ReviewDecision decision, String note) {
        Document d = authorizedQuery.getById(documents, Document.class, PermissionKeys.DOCUMENT_REVIEW, documentId);

        if (d.getStatus() == DocumentStatus.RETIRED) {
            throw new IllegalArgumentException("Document " + d.getId() + " is retired and cannot be reviewed");
        }

        DocumentVersion v = versions.versionAt(d.getId(), versionNo)
                .orElseThrow(() -> new NoSuchElementException("Not found"));

        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.DOCUMENT_REVIEW, d.getCaseId());
        applyWriteScope(c);

        v.setReviewStatus(decision == ReviewDecision.APPROVED ? ReviewStatus.APPROVED : ReviewStatus.REJECTED);
        v.setReviewedBy(contextProvider.current().userId());
        v.setReviewedAt(Instant.now(clock));
        v.setReviewNote(note);
        versions.save(v);

        if (decision == ReviewDecision.APPROVED) {
            for (DocumentRequest request : requests.fulfilledBy(d.getId())) {
                UUID requirementId = request.getRequirementId();
                if (requirementId == null) continue;

                // Skip the call entirely -- not merely rely on satisfy's own
                // idempotent early-return -- when the requirement is already
                // SATISFIED: satisfy's own gate (milestone.complete) and its
                // CaseOnHoldException check both run BEFORE that early-return,
                // so calling it at all would demand permission/hold conditions
                // a pure no-op has no business requiring. See this class's own
                // javadoc and RequirementRepository.byId's.
                Requirement requirement = requirementRepository.byId(requirementId).orElse(null);
                if (requirement != null && requirement.getStatus() == RequirementStatus.SATISFIED) continue;

                requirementService.satisfy(requirementId, d.getId(), DocumentService.SATISFIED_REF_TYPE);
            }
        } else {
            if (!requirementRepository.satisfiedBy(d.getId(), DocumentService.SATISFIED_REF_TYPE).isEmpty()) {
                requirementService.reopen(d.getId(), DocumentService.SATISFIED_REF_TYPE);
            }
        }

        return toVersionView(v);
    }

    /**
     * Design spec 5.4's own words: "an ordinary AuthorizedQuery listing
     * filtered to review_status = PENDING; it needs no carve-out." {@code
     * scoping.DocumentVersionDescriptor} already exists (Task 10) and is
     * permission-agnostic, so no new descriptor work is needed here -- this
     * is the first real service method to read {@link DocumentVersion}
     * through it under {@code document.review} specifically.
     */
    @RequirePermission(PermissionKeys.DOCUMENT_REVIEW)
    @Transactional(readOnly = true)
    public Page<DocumentVersionView> pending(Pageable pageable) {
        return authorizedQuery.findAll(versions, DocumentVersion.class, PermissionKeys.DOCUMENT_REVIEW,
                        pendingOnly(), pageable)
                .map(DocumentReviewService::toVersionView);
    }

    private static Specification<DocumentVersion> pendingOnly() {
        return (root, query, cb) -> cb.equal(root.get("reviewStatus"), ReviewStatus.PENDING);
    }

    /** The identical write_scope guard {@link DocumentService#applyWriteScope} applies. */
    private void applyWriteScope(Case c) {
        if (c.getCurrentStageId() == null) return;
        Stage stage = authorizedQuery.getById(stages, Stage.class, PermissionKeys.WORKFLOW_VIEW, c.getCurrentStageId());
        writeScope.check(c, stage);
    }

    private static DocumentVersionView toVersionView(DocumentVersion v) {
        return new DocumentVersionView(v.getId(), v.getDocumentId(), v.getVersionNo(), v.getSizeBytes(),
                v.getContentType(), v.getSha256(), v.getReviewStatus(), v.getReviewedBy(), v.getReviewedAt(),
                v.getReviewNote(), v.getUploadedBy(), v.getUploadedAt());
    }
}
