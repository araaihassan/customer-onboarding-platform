package co.ara.onboarding.document;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Two concurrent {@code share} calls raced past the Java-level idempotency
 * pre-check ({@code DocumentSharingService#liveShareTo}) for the same
 * {@code (document_id, principal_type, principal_id)} triple, and both
 * reached the database's own {@code document_share_live_uq} partial unique
 * index (live rows only) at once.
 *
 * <p>The loser CANNOT simply re-read and return the winner's row the way an
 * earlier version of {@code DocumentSharingService#share} tried to: Postgres
 * aborts the whole transaction on a unique-constraint violation, so any
 * further statement in that same transaction -- including the "just read the
 * winner's row back" recovery attempt -- fails with {@code 25P02 current
 * transaction is aborted}, and Hibernate additionally marks the session
 * rollback-only, so the recovery attempt surfaces as
 * {@code UnexpectedRollbackException} rather than ever returning a row. This
 * is exactly the same shape the codebase already has four precedents for --
 * {@code DocumentService#addVersion} ({@link DocumentVersionConflictException}),
 * {@code programme.ProgrammeMembershipService#saveLink}
 * ({@code ProgrammeJourneyAlreadyLinkedException}),
 * {@code customer.CustomerContactService} ({@code DuplicateContactEmailException}),
 * {@code provisioning.TenantProvisioningService}
 * ({@code DuplicateSlugException}) -- all of which throw a dedicated
 * exception from the catch block instead of attempting to recover inline.
 *
 * <p>Mapped to 409 by {@code @ResponseStatus} directly on this class, rather
 * than a {@code DocumentExceptionHandler}: no such handler exists in this
 * module yet (Task 22 adds one, alongside {@code DocumentController}), and
 * the three other exceptions this module already defines
 * ({@link DocumentVersionConflictException}, {@link UnacceptableContentTypeException},
 * {@link UploadTooLargeException}) all say "mapped by the controller (Task
 * 22)" and are genuinely unmapped until then. This one differs because the
 * fix that introduced it (Task 19 review) needed the correct status to hold
 * the moment it ships, not after a later task -- the annotation gives that
 * without needing to stand up a handler early. It stays correct once Task 22
 * adds {@code DocumentExceptionHandler} for the other three: Spring resolves
 * a type-level {@code @ResponseStatus} exactly as it would an
 * {@code @ExceptionHandler}, so nothing here needs to move.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateDocumentShareException extends RuntimeException {

    public DuplicateDocumentShareException(Throwable cause) {
        super("This document is already shared with that principal", cause);
    }
}
