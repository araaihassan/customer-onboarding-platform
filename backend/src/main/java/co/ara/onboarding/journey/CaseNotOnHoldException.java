package co.ara.onboarding.journey;

import java.util.UUID;

/** resume() was called on a case that is not currently ON_HOLD. */
public class CaseNotOnHoldException extends RuntimeException {

    public CaseNotOnHoldException(UUID caseId) {
        super("Case " + caseId + " is not on hold");
    }
}
