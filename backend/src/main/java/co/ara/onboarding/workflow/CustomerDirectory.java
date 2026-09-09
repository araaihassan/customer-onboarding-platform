package co.ara.onboarding.workflow;

import java.util.Optional;
import java.util.UUID;

/**
 * The facts port {@link CustomerTemplateService#clone} uses to resolve a
 * customer id it received in a request body, without workflow depending on
 * customer's entities or repositories directly. Empty means "not visible to
 * this caller" -- out of scope or a foreign tenant -- collapsing to
 * {@link CustomerTemplateService}'s own {@code NoSuchElementException} / 404,
 * never a raw finder result.
 *
 * <b>Why this exists instead of a direct {@code workflow -> customer}
 * dependency:</b> a direct dependency was the first design tried for Task 16
 * (sub-project 3A / QA Q21), and it compiles and passes every explicitly NAMED
 * {@code ModuleBoundaryTest} rule (neither {@code noWorkflowDependencyOnJourney}
 * nor {@code noJourneyDependencyOnCustomer} mentions {@code customer} as a
 * TARGET of a workflow dependency) -- but it fails the general
 * {@code noCyclesBetweenModules} slices check, which is exactly why that check
 * exists alongside the named ones. {@code customer} already depends on
 * {@code journey} ({@code customer.JourneyCustomerDirectory} implements
 * {@code journey.CustomerDirectory}), and {@code journey} already depends on
 * {@code workflow} ({@code CaseEngine} reads {@code Stage}/
 * {@code MilestoneDefinition}/etc.) -- so a third edge, {@code workflow ->
 * customer}, closes the cycle {@code customer -> journey -> workflow ->
 * customer}. Confirmed empirically: {@code ModuleBoundaryTest.noCyclesBetweenModules}
 * failed with exactly that three-slice cycle before this port replaced the
 * direct dependency.
 *
 * {@code programme.ProgrammeService}'s own direct {@code customer.Customer}
 * dependency is NOT the same shape and is not a precedent this class
 * contradicts: nothing depends on {@code programme}, so a {@code customer ->
 * programme} or {@code journey -> programme} edge could never close a loop
 * back through it. This interface is the same declared-by-the-consumer,
 * implemented-by-the-provider inversion {@code journey.CustomerDirectory}
 * already established ({@code customer.JourneyCustomerDirectory} is its
 * implementation) -- just with {@code workflow} as the consumer instead of
 * {@code journey}, and {@link CustomerSummary} as the narrower fact shape this
 * consumer actually needs.
 */
public interface CustomerDirectory {

    Optional<CustomerSummary> findVisible(UUID customerId);
}
