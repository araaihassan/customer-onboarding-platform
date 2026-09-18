package co.ara.onboarding.document;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Two concurrent {@code link} calls raced past the Java-level idempotency
 * pre-check ({@code DocumentSharingService#liveLinkTo}) for the same
 * {@code (document_id, case_id)} pair, and both reached the database's own
 * {@code document_case_link_live_uq} partial unique index (live rows only)
 * at once -- the identical shape {@link DuplicateDocumentShareException}
 * already documents in full for {@code document_share_live_uq}: the loser
 * cannot recover inline (Postgres aborts the whole transaction on a unique
 * violation, so even a "just read the winner's row back" attempt fails with
 * {@code 25P02 current transaction is aborted} and Hibernate marks the
 * session rollback-only on top), so it is refused here instead, exactly as
 * {@link DocumentSharingService#share}'s own loser is. See
 * {@link DuplicateDocumentShareException}'s own javadoc for the full
 * explanation this class deliberately does not repeat.
 *
 * <p>Mapped to 409 by {@code @ResponseStatus} directly on this class, the
 * same reasoning as {@link DuplicateDocumentShareException}: no
 * {@code DocumentExceptionHandler} exists in this module yet (Task 22 adds
 * one), and this exception needs the correct status to hold the moment it
 * ships.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateDocumentCaseLinkException extends RuntimeException {

    public DuplicateDocumentCaseLinkException(Throwable cause) {
        super("This document is already linked to that case", cause);
    }
}
