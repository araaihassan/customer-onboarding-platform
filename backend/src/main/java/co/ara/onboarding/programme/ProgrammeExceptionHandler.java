package co.ara.onboarding.programme;

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
 * Carries no handlers yet: create/read/update/deactivate (this task) defines no
 * domain-specific exception of its own. {@code NoSuchElementException} (a
 * foreign or out-of-scope programme or customer id) and bean-validation
 * failures (a blank name) are already mapped globally by
 * {@code platform.ApiExceptionHandler} to 404 and 400 respectively, and there is
 * no programme-specific conflict or state-transition rule the way
 * {@code task.IllegalTaskTransitionException} exists for task's status machine
 * -- a programme has no lifecycle of its own to have illegal transitions in
 * (QA Q20). Created now, ahead of any handler, purely so this module's
 * {@code @RestControllerAdvice} seam exists from its first service onward, the
 * same "one file per module" shape every other domain module carries; the
 * first genuinely programme-specific exception (Task 13's membership work, or
 * later) adds its handler here rather than opening a second file.
 */
@RestControllerAdvice
class ProgrammeExceptionHandler {
}
