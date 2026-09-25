package co.ara.onboarding.journey;

import java.util.UUID;

/**
 * journey's seam for the SIGNATURE half of the requirement-instantiation pattern
 * ({@link TaskLifecycle}, {@link DocumentRequestLifecycle}) -- a concrete
 * journey-side implementation naming an agreement type would violate
 * ModuleBoundaryTest.noJourneyDependencyOnAgreement, so
 * {@code agreement.AgreementLifecycleAdapter} implements this instead.
 *
 * Unlike {@link DocumentRequestLifecycle} it has TWO callers -- CaseService.create
 * and MigrationService -- so every implementation must be idempotent (spec 3.2):
 * calling it again on a case that already has a live agreement for a given
 * requirement must be a no-op, not a duplicate or an error.
 */
public interface AgreementLifecycle {
    void instantiateForCase(UUID caseId);
}
