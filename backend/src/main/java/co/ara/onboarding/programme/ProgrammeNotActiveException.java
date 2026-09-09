package co.ara.onboarding.programme;

import java.util.UUID;

/**
 * {@code ProgrammeService.update} was called against a programme whose
 * {@code status} is not {@code ACTIVE}.
 *
 * This is an independent, service-level guard, not a consequence of the
 * descriptor. {@code scoping.ProgrammeDescriptor.assignedScope} requires
 * {@code Programme.status = ACTIVE} for a participation-mediated (ASSIGNED)
 * reader -- so deactivation structurally cuts off that narrow grant's read,
 * by design (see that class's own doc comment). But {@code departmentScope}/
 * {@code teamScope} were deliberately left unchanged: a DEPARTMENT- or
 * TEAM-scoped holder can still SEE a deactivated programme, for governance and
 * reporting reasons. The same descriptor backs {@code programme.manage}, so
 * without this guard a DEPARTMENT- or TEAM-scoped {@code programme.manage}
 * holder could still successfully target and mutate an already-deactivated
 * programme through {@code update} -- full write access surviving
 * deactivation, for anyone whose grant resolves through the broader scopes.
 * Currently latent (only Administrator holds {@code programme.manage} today,
 * per Task 11's still-open {@code RoleTemplateCoverageTest} exception), but it
 * becomes live the moment a narrower role is seeded to hold it.
 *
 * Follows {@code auth.RefreshTokenService.rotate}'s precedent: a write path
 * refusing a non-ACTIVE record independently of whatever query/descriptor
 * layer let the caller reach it in the first place, because deactivation is
 * never the only way a status changes and the next path to change one will
 * not remember to re-check every reader's scope. Mapped to 409, matching
 * {@code journey}'s own "the request was understood, but the record's current
 * state does not allow it" shape ({@code CaseOnHoldException},
 * {@code CaseNotOnHoldException}, et al.) -- retryable in principle (a future
 * reactivation path would clear it), which is what separates this from a 422
 * or a 403. Deliberately narrower than a generic "wrong state" type: {@code
 * ProgrammeStatus} has exactly two values and no transition machine of its
 * own (QA Q20), so this exception names the one illegal write directly rather
 * than modelling a state machine that does not otherwise exist.
 */
public class ProgrammeNotActiveException extends RuntimeException {

    public ProgrammeNotActiveException(UUID programmeId) {
        super("Programme " + programmeId + " is not active");
    }
}
