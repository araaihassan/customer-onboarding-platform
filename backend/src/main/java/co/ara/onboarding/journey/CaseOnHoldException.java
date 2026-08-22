package co.ara.onboarding.journey;

import java.util.UUID;

/**
 * A write that would change case progress was attempted while the case is
 * ON_HOLD. Task 18 owns the hold/resume transition itself; this owns the refusal
 * every progress-changing write must give until then.
 */
public class CaseOnHoldException extends RuntimeException {

    public CaseOnHoldException(UUID caseId) {
        super("Case " + caseId + " is on hold");
    }
}
