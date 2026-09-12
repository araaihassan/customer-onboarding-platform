package co.ara.onboarding.programme;

import java.util.UUID;

/**
 * {@code ProgrammeMembershipService.addJourney} was called for a case that is
 * still actively linked to a programme -- this one, or a different one.
 *
 * {@code programme_case_active_uq} is a PARTIAL unique index on
 * {@code case_id} (only where {@code removed_at IS NULL}): a case belongs to
 * at most one programme AT A TIME, tenant-wide, never per-programme -- so this
 * collision can come from a DIFFERENT programme's still-active link, one this
 * caller's own {@code programme.manage} scope need not even reach. That is why
 * this is caught from the database's own {@code DataIntegrityViolationException}
 * in {@link ProgrammeMembershipService#addJourney} rather than pre-checked
 * through {@code AuthorizedQuery} first, which would silently miss an active
 * link outside the caller's own scope and let the INSERT reach the database
 * anyway. A case previously REMOVED from a programme carries no such
 * collision -- its old row's {@code removed_at} is set, so it falls outside
 * the partial index -- so re-linking it, to this programme or another, is
 * always a clean INSERT and never reaches this exception.
 *
 * Mapped to 409 by {@link ProgrammeExceptionHandler}, the same "understood,
 * but the record's current state does not allow it" shape as
 * {@link ProgrammeNotActiveException}. Deliberately does not name the OTHER
 * programme in its message -- the caller may not be authorized to know it
 * exists.
 */
public class ProgrammeJourneyAlreadyLinkedException extends RuntimeException {

    public ProgrammeJourneyAlreadyLinkedException(UUID caseId, Throwable cause) {
        super("Case " + caseId + " is already actively linked to a programme", cause);
    }
}
