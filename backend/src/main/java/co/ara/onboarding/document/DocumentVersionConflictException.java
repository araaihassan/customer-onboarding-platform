package co.ara.onboarding.document;

import java.util.UUID;

/**
 * Two callers appended a version to the same document at the same moment.
 * {@code document_version_no_uq (document_id, version_no)} is the truth,
 * not a pre-check -- appending a version derives no state, unlike
 * {@code CaseEngine.reconcile}, so there is no row lock to take the way
 * {@code CaseRepository.lockById} serialises reconciliation (spec §4.2).
 *
 * Caught from the database's own {@code DataIntegrityViolationException} in
 * {@link DocumentService#addVersion}, the same shape
 * {@code programme.ProgrammeJourneyAlreadyLinkedException} already uses for
 * its own unique-index collision. The loser's blob was still written to the
 * store before this constraint fired -- {@code BlobStore.put} always runs
 * before the row insert it is racing against -- so it becomes a harmless,
 * unreferenced orphan (§7.4's write ordering; §2.2's "orphaned-blob
 * sweeping" out-of-scope note), never a row pointing at bytes that were
 * never written.
 *
 * Mapped to 409 by the controller (Task 22).
 */
public class DocumentVersionConflictException extends RuntimeException {

    public DocumentVersionConflictException(UUID documentId, Throwable cause) {
        super("A version was already added to document " + documentId
                + " concurrently; retry", cause);
    }
}
