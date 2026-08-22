package co.ara.onboarding.journey;

import java.util.UUID;

/** case.advance was called on a case whose current stage is not yet exitable. */
public class StageNotExitableException extends RuntimeException {

    public StageNotExitableException(UUID caseId) {
        super("Case " + caseId + "'s current stage is not exitable");
    }
}
