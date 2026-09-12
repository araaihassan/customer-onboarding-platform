package co.ara.onboarding.programme;

import java.util.UUID;

/**
 * {@code ProgrammeMembershipService.addParticipant} was called for a user who
 * already holds an ACTIVE {@code programme_participant} row on this programme.
 *
 * {@code programme_participant_uq} is a PLAIN unique index on
 * {@code (programme_id, user_id)} -- not partial on status -- so at most one
 * row can ever exist for this pair, ACTIVE or REMOVED. Distinguishing "already
 * active" (a real conflict, this exception) from "previously removed"
 * (silently reactivated -- see {@link ProgrammeMembershipService#addParticipant})
 * is what keeps a genuine double-add a clean 409 instead of either a silent
 * no-op or a raw {@code DataIntegrityViolationException} (500).
 *
 * Mapped to 409 by {@link ProgrammeExceptionHandler}, the same "understood,
 * but the record's current state does not allow it" shape as
 * {@link ProgrammeNotActiveException}.
 */
public class ProgrammeParticipantAlreadyActiveException extends RuntimeException {

    public ProgrammeParticipantAlreadyActiveException(UUID programmeId, UUID userId) {
        super("User " + userId + " is already an active participant of programme " + programmeId);
    }
}
