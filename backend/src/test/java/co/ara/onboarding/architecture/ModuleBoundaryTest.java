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
     * TenantDebugController is the one exemption: it injects TenantRepository
     * and is deleted in Task 20, which removes this clause with it. Excluded by
     * name rather than by weakening the rule, so any NEW controller is still
     * caught.
     */
    @ArchTest
    static final ArchRule controllersDoNotUseRepositoriesDirectly =
            noClasses().that().haveSimpleNameEndingWith("Controller")
                .and().doNotHaveSimpleName("TenantDebugController")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .because("a repository call outside a @Transactional service has no tenant bound")
                .allowEmptyShould(true);
}
