package co.ara.onboarding.programme;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * programme's own exception-to-HTTP mapping, following {@code task.
 * TaskExceptionHandler}'s and {@code journey.JourneyExceptionHandler}'s exact
 * pattern -- a package-private-eligible {@code @RestControllerAdvice} living in
 * the module that would otherwise define the domain exception, since
 * {@code platform.ApiExceptionHandler} must never name one (a domain type there
 * closes a {@code platform -> programme -> platform} cycle,
 * {@code ModuleBoundaryTest.noCyclesBetweenModules}).
 *
 * {@code NoSuchElementException} (a foreign or out-of-scope programme or
 * customer id) and bean-validation failures (a blank name) are already mapped
 * globally by {@code platform.ApiExceptionHandler} to 404 and 400
 * respectively. All three exceptions handled here share the same 409 shape:
 * the caller CAN see and target this record (it is in their scope), the
 * request was understood, and the refusal is retryable in principle once the
 * record's state changes -- exactly {@code journey.JourneyExceptionHandler
 * .onConflict}'s own reasoning for {@code CaseOnHoldException} et al.
 * {@link ProgrammeNotActiveException} is the original one (a fix round on
 * Task 12, not that task's original scope); {@link
 * ProgrammeParticipantAlreadyActiveException} and {@link
 * ProgrammeJourneyAlreadyLinkedException} were added in Task 13's own fix
 * round, closing the two membership-mutation paths that previously let a
 * duplicate/collision write surface as a raw 500.
 */
@RestControllerAdvice
class ProgrammeExceptionHandler {

    @ExceptionHandler({ProgrammeNotActiveException.class, ProgrammeParticipantAlreadyActiveException.class,
                       ProgrammeJourneyAlreadyLinkedException.class})
    ProblemDetail onConflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
