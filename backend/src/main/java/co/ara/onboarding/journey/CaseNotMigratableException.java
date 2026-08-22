package co.ara.onboarding.journey;

import java.util.UUID;

/**
 * migrate() refuses an ineligible case rather than silently skipping it -- a
 * partially applied bulk action whose failures are invisible is worse than one
 * that stops. Carries the same reason preview() would have given the caller.
 */
public class CaseNotMigratableException extends RuntimeException {

    public CaseNotMigratableException(UUID caseId, String reason) {
        super("Case " + caseId + " cannot be migrated: " + reason);
    }
}
