package co.ara.onboarding.document;

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
 * <p>Originally mapped to 409 by a bare {@code @ResponseStatus} directly on
 * this class (Task 19, before {@code DocumentExceptionHandler} existed) --
 * Task 22 removed that annotation once the handler's own
 * {@code onConflict(...)} took over: a bare {@code @ResponseStatus} produces
 * Spring's default (non-{@link org.springframework.http.ProblemDetail})
 * error body, and {@code ExceptionHandlerExceptionResolver} resolves an
 * {@code @ExceptionHandler} method before {@code
 * ResponseStatusExceptionResolver} ever gets to the annotation anyway, so
 * leaving both in place would have left the annotation dead code reaching
 * nothing. See {@code DocumentExceptionHandler}'s own javadoc for the full
 * reasoning.
 */
public class DuplicateDocumentShareException extends RuntimeException {

    public DuplicateDocumentShareException(Throwable cause) {
        super("This document is already shared with that principal", cause);
    }
}
