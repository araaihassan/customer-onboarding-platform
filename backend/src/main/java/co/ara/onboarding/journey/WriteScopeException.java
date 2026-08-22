package co.ara.onboarding.journey;

import co.ara.onboarding.workflow.WriteScope;

/**
 * A stage's write_scope narrowed who may write here, and the caller -- however
 * widely they are granted the underlying permission -- is not in it. Subtractive
 * only: this is thrown, never used to widen anything (see StageWriteScopeGuard).
 */
public class WriteScopeException extends RuntimeException {

    public WriteScopeException(String stageName, WriteScope scope) {
        super("Stage \"" + stageName + "\" write scope " + scope + " does not admit the caller");
    }
}
