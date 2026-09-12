package co.ara.onboarding.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(
        packages = "co.ara.onboarding",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    @ArchTest
    static final ArchRule noCyclesBetweenModules =
            slices().matching("co.ara.onboarding.(*)..").should().beFreeOfCycles();

    /**
     * allowEmptyShould(true): no *Service classes exist until Task 8. ArchUnit
     * fails a rule whose should() matched nothing, so without this the rule
     * would go red for having nothing to check rather than for a violation.
     * The rule starts binding to real classes from Task 8 onward.
     */
    @ArchTest
    static final ArchRule servicesDoNotDependOnControllers =
            noClasses().that().haveSimpleNameEndingWith("Service")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Controller")
                .because("controllers are an entry point, never a dependency of the domain")
                .allowEmptyShould(true);

    /**
     * Carried forward from Task 4's review. Spring Data repository proxies get
     * their own transaction handling and do NOT trigger TenantTransactionBinder,
     * so a repository called outside an enclosing @Transactional service method
     * runs with no tenant bound. RLS then fails closed and the query returns
     * nothing — silently, with no error to follow.
     *
     * Controllers must therefore never touch a repository directly.
     *
     * There are no exemptions. TenantDebugController held the only one and was
     * deleted in Task 20 along with this clause, so the rule now applies to every
     * controller in the codebase without qualification.
     */
    @ArchTest
    static final ArchRule controllersDoNotUseRepositoriesDirectly =
            noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .because("a repository call outside a @Transactional service has no tenant bound");

    /**
     * The cycle rule alone would pass a one-way violation, and a one-way violation is
     * exactly what erodes here: workflow reaching into journey to answer "how many
     * cases are on v4" would compile, pass every other test, and quietly make a
     * definition module depend on runtime state.
     */
    @ArchTest
    static final ArchRule noWorkflowDependencyOnJourney =
            noClasses().that().resideInAPackage("..workflow..")
                .should().dependOnClassesThat().resideInAPackage("..journey..")
                .because("a version describes an executable definition; where a case sits is journey's alone");

    /**
     * journey consumes journey.CustomerDirectory, which customer implements. The arrow
     * therefore runs customer -> journey, and journey holds no customer entity, no
     * customer repository, and no CustomerStatus.
     */
    @ArchTest
    static final ArchRule noJourneyDependencyOnCustomer =
            noClasses().that().resideInAPackage("..journey..")
                .should().dependOnClassesThat().resideInAPackage("..customer..")
                .because("journey consumes CustomerDirectory, never customer's entities or repositories");

    /**
     * Its own named rule rather than folded into the cycle check: a one-way
     * journey -> task import would still pass a plain no-cycles test, and the whole
     * reason TaskDirectory and TaskLifecycle exist is to make that import
     * unnecessary. Same reasoning as noWorkflowDependencyOnJourney.
     */
    @ArchTest
    static final ArchRule noJourneyDependencyOnTask =
            noClasses().that().resideInAPackage("co.ara.onboarding.journey..")
                .should().dependOnClassesThat().resideInAPackage("co.ara.onboarding.task..")
                .because("a one-way journey -> task import would still pass a plain no-cycles test; "
                        + "TaskDirectory and TaskLifecycle exist precisely to make that import unnecessary");

    /**
     * Its own named rule rather than folded into the cycle check: a one-way
     * journey -> programme import would still pass a plain no-cycles test.
     * programme depends on journey, never the reverse.
     */
    @ArchTest
    static final ArchRule noJourneyDependencyOnProgramme =
            noClasses().that().resideInAPackage("co.ara.onboarding.journey..")
                .should().dependOnClassesThat().resideInAPackage("co.ara.onboarding.programme..")
                .because("programme depends on journey, never the reverse. Membership lives in "
                       + "programme_case precisely so journey never learns programmes exist.");

    /**
     * Its own named rule rather than folded into the cycle check: programme reaches
     * customer through a facts port (CustomerDirectory inversion). A customer -> programme
     * import would close the cycle.
     */
    @ArchTest
    static final ArchRule noCustomerDependencyOnProgramme =
            noClasses().that().resideInAPackage("co.ara.onboarding.customer..")
                .should().dependOnClassesThat().resideInAPackage("co.ara.onboarding.programme..")
                .because("programme reaches customer through a facts port, the CustomerDirectory "
                       + "inversion. An import back would close the cycle.");

    /**
     * Its own named rule rather than folded into the cycle check: a one-way
     * journey -> document import would still pass a plain no-cycles test.
     * document depends on journey, never the reverse. There is deliberately NO
     * DocumentDirectory port (spec 3.2).
     */
    @ArchTest
    static final ArchRule noJourneyDependencyOnDocument =
            noClasses().that().resideInAPackage("..journey..")
                .should().dependOnClassesThat().resideInAPackage("..document..")
                .because("document depends on journey, never the reverse. A one-way import "
                       + "would still pass the plain no-cycles rule, which is why this is its "
                       + "own named rule -- the same reasoning as noJourneyDependencyOnCustomer. "
                       + "There is deliberately NO DocumentDirectory port (spec 3.2).");

    /**
     * Its own named rule rather than folded into the cycle check: a one-way
     * workflow -> document import would still pass a plain no-cycles test.
     * document depends on workflow's definitions read-only, never the reverse.
     */
    @ArchTest
    static final ArchRule noWorkflowDependencyOnDocument =
            noClasses().that().resideInAPackage("..workflow..")
                .should().dependOnClassesThat().resideInAPackage("..document..")
                .because("document depends on workflow, never the reverse. A one-way import "
                       + "would still pass the plain no-cycles rule, which is why this is its "
                       + "own named rule -- the same reasoning as noWorkflowDependencyOnJourney.");

    /**
     * Its own named rule rather than folded into the cycle check, even though
     * spec 3.1's dependency list for document (platform, tenancy, authz, audit,
     * identity, workflow, journey, customer) does not include task at all --
     * document has no relationship to task today, in either direction. The
     * task.attachment_ref/attachment_ref_type seam CLAUDE.md's "What sub-project
     * 4 inherits" describes is a pattern precedent document may follow, not an
     * actual code dependency. This rule exists preemptively, for the same
     * reason a one-way import would still pass the plain no-cycles rule: task
     * must never import document, mirroring the one-way discipline already
     * enforced for journey and workflow above.
     */
    @ArchTest
    static final ArchRule noTaskDependencyOnDocument =
            noClasses().that().resideInAPackage("..task..")
                .should().dependOnClassesThat().resideInAPackage("..document..")
                .because("task has no current relationship to document at all -- this rule exists "
                       + "preemptively, the same reasoning as noJourneyDependencyOnTask: a one-way "
                       + "import would still pass the plain no-cycles rule.");
}
