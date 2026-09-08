package co.ara.onboarding.journey;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.workflow.Stage;
import org.springframework.stereotype.Component;

/**
 * Subtractive only. It runs AFTER @RequirePermission has said yes and AFTER
 * AuthorizedQuery has resolved the record, and every branch either refuses or does
 * nothing -- there is no path here that grants. A second mechanism able to widen
 * authority would be a parallel authorization system, which is the one thing this
 * codebase must not grow.
 *
 * A separate class rather than a method on the service so there is exactly one
 * place to read, and so a reviewer can see that it has no positive branch.
 *
 * Public (Task 16): co.ara.onboarding.task.TaskService, a different package,
 * needs to gate its own writes through the same stage write_scope a case's
 * other mutations already go through, rather than re-deriving the check or
 * skipping it. Widening visibility is safe precisely because the class is
 * subtractive only -- it has no branch that grants, so exposing it cannot
 * hand out authority it didn't already have inside journey. Case, Milestone
 * and Stage (the parameter types) are themselves public already.
 */
@Component
public class StageWriteScopeGuard {

    private final AuthContextProvider contextProvider;

    StageWriteScopeGuard(AuthContextProvider contextProvider) {
        this.contextProvider = contextProvider;
    }

    public void check(Case c, Milestone m, Stage stage) {
        AuthContext ctx = contextProvider.current();
        boolean allowed = switch (stage.getWriteScope()) {
            case ANY        -> true;
            case OWNER_ONLY -> ctx.userId().equals(m.getOwnerUserId())
                             || ctx.userId().equals(c.getOwnerUserId());
            case TEAM       -> c.getOwningTeamId() != null && ctx.teamIds().contains(c.getOwningTeamId());
            case DEPARTMENT -> c.getOwningDepartmentId() != null
                             && c.getOwningDepartmentId().equals(ctx.departmentId());
        };
        if (!allowed) throw new WriteScopeException(stage.getName(), stage.getWriteScope());
    }
}
