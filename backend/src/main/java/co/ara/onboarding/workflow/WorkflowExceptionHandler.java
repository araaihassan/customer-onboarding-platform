package co.ara.onboarding.workflow;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * workflow's own exception-to-HTTP mapping.
 *
 * Here rather than in platform.ApiExceptionHandler for the reason that module's own
 * comment gives: platform is the foundation every domain builds on, and workflow
 * already depends on it, so a platform class naming DraftAlreadyExistsException would
 * close a platform -> workflow -> platform cycle. ModuleBoundaryTest.noCyclesBetweenModules
 * rejects exactly that.
 *
 * Three of the four handlers return ProblemDetail, not an invented "ApiError" type --
 * customer.CustomerExceptionHandler, authz.AuthzExceptionHandler and
 * auth.AuthExceptionHandler already established that as this codebase's one shared
 * error DTO, and Spring resolves the response status from the ProblemDetail instance
 * itself, so none of the three carries its own @ResponseStatus. onValidation is the
 * exception: its body is a typed problem list, not a generic detail string, because
 * the builder renders each entry against its own stage row rather than showing one
 * opaque message.
 */
@RestControllerAdvice
class WorkflowExceptionHandler {

    /**
     * 422, not 400: the request was well-formed and the graph was understood -- it was
     * semantically rejected, and the builder renders the list against its stage rows.
     */
    @ExceptionHandler(PublishValidationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ProblemList onValidation(PublishValidationException e) {
        return new ProblemList(e.problems());
    }

    /**
     * A second open draft on the same template (V12's partial unique index), not a
     * raw 500. Carries versionId so the caller can resume or discard the draft that
     * is actually blocking it, rather than dead-ending on the message.
     */
    @ExceptionHandler(DraftAlreadyExistsException.class)
    ProblemDetail onDuplicateDraft(DraftAlreadyExistsException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("versionId", e.versionId());
        return problem;
    }

    /** Someone else saved this draft first; the builder should say so, not silently overwrite it. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail onStale(OptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * A frozen (published) version rejecting a write, or a branch/fallback/dependency
     * key naming no declared element: both are malformed requests, not server failures.
     */
    @ExceptionHandler({VersionNotEditableException.class, UnknownReferenceException.class})
    ProblemDetail onBadRequest(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    public record ProblemList(List<String> problems) {}
}
