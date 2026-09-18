package co.ara.onboarding.document;

import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.customer.CustomerContact;
import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.customer.OrgUnitResolver;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.StageWriteScopeGuard;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 19 (design spec 4.3/6.3): the WRITE half of Q9's "restricted until
 * explicitly shared" escape hatch for the SENSITIVE tier -- the READ half
 * ({@code scoping.DocumentAudienceFilter}'s {@code sharedWith} EXISTS
 * subquery, over both the internal and portal branches) was already built and
 * reviewed by Tasks 9-13; this class is the first thing that ever writes a
 * {@link DocumentShare} row through a gated, id-resolved path rather than a
 * test seeding one directly.
 *
 * A separate class from {@link DocumentService} on purpose (the plan's own
 * "Files" line) -- Task 20 adds this class's other half,
 * {@code link}/{@code unlink} over {@link DocumentCaseLink}, which is a
 * different authored-relationship table entirely (design spec 4.4, "one
 * answers WHO, the other answers WHERE").
 *
 * Every id this class receives from a caller -- {@code documentId},
 * {@code shareId}, and {@code principalId} itself -- is resolved through
 * {@link AuthorizedQuery} (or, for a department, {@link OrgUnitResolver})
 * before anything is written, the same write-path invariant
 * {@code task.TaskService}'s own class javadoc names and CLAUDE.md states
 * outright: "every id a write path takes from a URL or a request body ...
 * before it writes." Resolving {@code documentId} under
 * {@code document.share} itself (never {@code document.manage}) means this
 * is narrowed by {@code DocumentAudienceFilter}'s ordinary targeting/sharing
 * audience, not the manage-only bypass {@code DocumentService#patch}'s own
 * javadoc documents -- a deliberate, already-recorded consequence of that
 * filter's own design (its javadoc names this exact method by name): sharing
 * a Legal-targeted document is refused to anyone outside Legal, ALL-scoped
 * document.share holders included. The recovery path is the one already
 * named there -- retarget via {@code document.manage} first, then share.
 */
@Service
public class DocumentSharingService {

    private static final String DOCUMENT_SHARE_LIVE_UNIQUE = "document_share_live_uq";

    private final DocumentRepository documents;
    private final DocumentShareRepository shares;
    private final CaseRepository cases;
    private final StageRepository stages;
    private final CustomerContactRepository contacts;
    private final AppUserRepository users;
    private final OrgUnitResolver orgUnits;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final StageWriteScopeGuard writeScope;
    private final Clock clock;

    public DocumentSharingService(DocumentRepository documents, DocumentShareRepository shares,
                                  CaseRepository cases, StageRepository stages,
                                  CustomerContactRepository contacts, AppUserRepository users,
                                  OrgUnitResolver orgUnits, AuthorizedQuery authorizedQuery,
                                  AuthContextProvider contextProvider, StageWriteScopeGuard writeScope,
                                  Clock clock) {
        this.documents = documents;
        this.shares = shares;
        this.cases = cases;
        this.stages = stages;
        this.contacts = contacts;
        this.users = users;
        this.orgUnits = orgUnits;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.writeScope = writeScope;
        this.clock = clock;
    }

    /**
     * Grants a document to one principal, past whatever the tier/targeting
     * audience would otherwise reach (design spec 4.3). {@code principalId}
     * is resolved per {@code principalType} before anything is written:
     *
     * <ul>
     *   <li>{@code CONTACT}: through {@link AuthorizedQuery} under
     *       {@code contact.view} -- composing that READ permission with this
     *       method's own {@code document.share} WRITE gate, the exact shape
     *       {@code task.TaskService#resolveAssigneeId} already uses composing
     *       {@code user.view} with {@code task.manage}. Refused as an
     *       {@link IllegalArgumentException} (400, not 404: this is a
     *       cross-reference check between two already-resolved records, the
     *       same "confused deputy" shape as {@code task.TaskService}'s own
     *       case/milestone mismatch guards, never the acting actor's own
     *       scope) when the resolved contact's {@code customerId} differs
     *       from the document's own -- Task 13's own ruling, carried forward
     *       here.</li>
     *   <li>{@code USER}: through {@link AuthorizedQuery} under
     *       {@code user.view}, {@code resolveAssigneeId}'s identical
     *       pattern.</li>
     *   <li>{@code DEPARTMENT}: through {@link OrgUnitResolver}, the same
     *       component {@link DocumentService#upload}/{@code patch} already
     *       use for {@code targetDepartmentId}.</li>
     * </ul>
     *
     * {@code document_share_live_uq} (a partial unique index over LIVE rows
     * only) means re-sharing an already-live principal is not automatically
     * safe -- handled here in two layers rather than left to surface as a raw
     * 500: an explicit pre-check returns the existing live share idempotently
     * (the common case, and the one a caller retrying a share request
     * actually wants), and the insert itself is still wrapped in case two
     * concurrent callers race the pre-check. That race CANNOT be recovered
     * from inline the way an earlier version of this method tried to --
     * Postgres aborts the whole transaction on the unique violation, so any
     * further statement (including a "just re-read the winner's row" attempt)
     * fails with {@code 25P02 current transaction is aborted} and Hibernate
     * marks the session rollback-only on top, surfacing as
     * {@code UnexpectedRollbackException} rather than ever returning a row.
     * The loser is instead refused with {@link DuplicateDocumentShareException}
     * (409) -- the same "throw a dedicated conflict exception, never try to
     * recover in the same transaction" idiom {@code DocumentService#addVersion},
     * {@code programme.ProgrammeMembershipService},
     * {@code customer.CustomerContactService} and
     * {@code provisioning.TenantProvisioningService} all already use for their
     * own unique-index collisions. A caller that actually wants the
     * idempotent "already shared" outcome gets it from the pre-check above on
     * a retry, not from this branch.
     *
     * <p>Refuses with {@link IllegalStateException} (409, mapped globally by
     * {@code platform.ApiExceptionHandler}, the same "role still has users
     * assigned" shape {@code authz.RoleService.deleteRole} already uses) when
     * the document is {@link DocumentStatus#RETIRED} -- otherwise a new share
     * could silently re-grant access that {@link DocumentService#retire}'s
     * own cascade exists specifically to close.
     *
     * {@link StageWriteScopeGuard} narrows on top, the same pattern
     * {@link DocumentService#upload}/{@code addVersion}/{@code patch}/
     * {@code retire} all use -- sharing is still a write against the case's
     * document, and an {@code OWNER_ONLY} stage narrows it the same way
     * regardless of which permission gates the call.
     */
    @RequirePermission(PermissionKeys.DOCUMENT_SHARE)
    @Transactional
    public DocumentShareView share(UUID documentId, SharePrincipalType principalType, UUID principalId) {
        if (principalType == null || principalId == null) {
            throw new IllegalArgumentException("principalType and principalId are required");
        }

        Document d = authorizedQuery.getById(documents, Document.class, PermissionKeys.DOCUMENT_SHARE, documentId);
        if (d.getStatus() == DocumentStatus.RETIRED) {
            throw new IllegalStateException("Document " + d.getId() + " is retired and cannot be shared");
        }
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.DOCUMENT_SHARE, d.getCaseId());
        applyWriteScope(c);

        UUID resolvedPrincipalId = resolvePrincipal(principalType, principalId, d);

        Optional<DocumentShare> existingLive = liveShareTo(d.getId(), principalType, resolvedPrincipalId);
        if (existingLive.isPresent()) {
            return toView(existingLive.get());
        }

        UUID actor = contextProvider.current().userId();
        DocumentShare share = new DocumentShare(Uuid7.generate(), d.getTenantId(), d.getId(),
                principalType, resolvedPrincipalId, actor, Instant.now(clock));
        try {
            shares.saveAndFlush(share);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, DOCUMENT_SHARE_LIVE_UNIQUE)) {
                throw new DuplicateDocumentShareException(e);
            }
            // Every other constraint is rethrown untouched -- reporting an
            // unrelated violation as a duplicate share would send the caller
            // hunting for a conflict that does not exist.
            throw e;
        }
        return toView(share);
    }

    /**
     * Ends a share -- a column, never a DELETE (design spec 4.3, and
     * {@code document_share} has no {@code GRANT DELETE} at all). Idempotent:
     * a share already revoked is left exactly as it was, never re-stamped
     * with a new {@code revokedAt} -- the same idempotence
     * {@link DocumentService#retire}'s own share-revocation cascade already
     * proves for the RETIRE path; this is the same rule for the single-share
     * path.
     *
     * {@code shareId} is resolved through {@link AuthorizedQuery} under
     * {@code document.share} first -- {@code scoping.DocumentShareDescriptor}
     * dispatches this by entity type, resolving DEPARTMENT/TEAM/ASSIGNED
     * through the share's own parent document's case, exactly as
     * {@link #share} itself is scoped. The parent {@link Document} and
     * {@link Case} are then re-resolved (never trusted from the already-loaded
     * {@link DocumentShare} row without going back through
     * {@link AuthorizedQuery}) purely so {@link StageWriteScopeGuard} narrows
     * on top, the same "resolve, then apply write_scope" shape every other
     * write in this module and {@code task} already use.
     *
     * On the very next read, {@code scoping.DocumentAudienceFilter}'s
     * {@code sharedWith} EXISTS subquery (already built, Tasks 9/13) filters
     * on {@code revoked_at IS NULL} -- so revocation needs no cache
     * invalidation of any kind, by construction.
     */
    @RequirePermission(PermissionKeys.DOCUMENT_SHARE)
    @Transactional
    public DocumentShareView revokeShare(UUID shareId) {
        DocumentShare share = authorizedQuery.getById(shares, DocumentShare.class, PermissionKeys.DOCUMENT_SHARE, shareId);
        Document d = authorizedQuery.getById(documents, Document.class, PermissionKeys.DOCUMENT_SHARE, share.getDocumentId());
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.DOCUMENT_SHARE, d.getCaseId());
        applyWriteScope(c);

        if (share.getRevokedAt() == null) {
            share.setRevokedAt(Instant.now(clock));
            shares.saveAndFlush(share);
        }
        return toView(share);
    }

    private UUID resolvePrincipal(SharePrincipalType type, UUID principalId, Document d) {
        return switch (type) {
            case CONTACT -> resolveContact(principalId, d);
            case USER -> authorizedQuery.getById(users, AppUser.class, PermissionKeys.USER_VIEW, principalId).getId();
            case DEPARTMENT -> orgUnits.resolveDepartment(principalId);
        };
    }

    /**
     * Task 13's own ruling, carried forward: a CONTACT principal whose
     * {@code customerId} differs from the document's own is refused as a 400
     * -- a cross-reference validation between two already-resolved records,
     * not the acting actor's own scope, so this is deliberately NOT the 404
     * an out-of-scope or nonexistent id gets ({@link AuthorizedQuery#getById}
     * already produces that on its own for a contact the actor cannot see at
     * all, or one that does not exist in this tenant).
     */
    private UUID resolveContact(UUID principalId, Document d) {
        CustomerContact contact = authorizedQuery.getById(
                contacts, CustomerContact.class, PermissionKeys.CONTACT_VIEW, principalId);
        if (!contact.getCustomerId().equals(d.getCustomerId())) {
            throw new IllegalArgumentException(
                    "Contact " + contact.getId() + " belongs to a different customer than document " + d.getId());
        }
        return contact.getId();
    }

    private Optional<DocumentShare> liveShareTo(UUID documentId, SharePrincipalType type, UUID principalId) {
        return shares.liveSharesOf(documentId).stream()
                .filter(s -> s.getPrincipalType() == type && s.getPrincipalId().equals(principalId))
                .findFirst();
    }

    /** Same guard, same reasoning, as {@link DocumentService#applyWriteScope} -- see its own javadoc. */
    private void applyWriteScope(Case c) {
        if (c.getCurrentStageId() == null) return;
        Stage stage = authorizedQuery.getById(stages, Stage.class, PermissionKeys.WORKFLOW_VIEW, c.getCurrentStageId());
        writeScope.check(c, stage);
    }

    /** Same idiom as {@code DocumentService.violates} -- matched on constraint name, never message text. */
    private static boolean violates(Throwable failure, String constraintName) {
        for (Throwable t = failure; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && constraintName.equals(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private static DocumentShareView toView(DocumentShare s) {
        return new DocumentShareView(s.getId(), s.getDocumentId(), s.getPrincipalType(),
                s.getPrincipalId(), s.getGrantedBy(), s.getGrantedAt(), s.getRevokedAt());
    }
}
