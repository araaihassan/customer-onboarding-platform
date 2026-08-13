package co.ara.onboarding.architecture;

import co.ara.onboarding.authz.RequirePermission;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchIgnore;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * Every public method on a *Service that a controller can reach must carry an
 * explicit permission gate (spec 6.10).
 *
 * Disabled deliberately, and it is worth being precise about why. The rule
 * itself is correct and has been verified to bind and to fail (see the commit
 * that introduced this file), but no *Service class exists until Task 8, and
 * ArchUnit fails a rule whose should() matched nothing rather than passing it
 * vacuously — confirmed by observing exactly that error against
 * ModuleBoundaryTest. So enabling it now yields a red build that says
 * "failed to check any classes", which is noise, not a finding.
 *
 * Ignoring is used here in preference to .allowEmptyShould(true) on purpose:
 * allowEmptyShould would go green now and stay green, so nobody would revisit
 * it, whereas an ignored test is visible in every test report as an outstanding
 * obligation.
 *
 * It must be @ArchIgnore, not JUnit's @Disabled. @ArchTest fields are collected
 * by ArchUnit's own JUnit 5 TestEngine, which does not process Jupiter
 * annotations — @Disabled here is silently inert and the rule runs anyway. That
 * was observed, not assumed: with @Disabled in place this class still failed
 * with "failed to check any classes".
 *
 * RE-ENABLE AT TASK 9, not Task 13. Task 9's RoleService is the first *Service
 * class in the codebase (Task 8 adds the permission catalog but no service), and
 * RequirePermission already exists as of Task 7, so each service can declare its
 * key as it is written. Task 13 adds PermissionGateAspect, which makes those
 * declarations enforceable — deferring the rule until then would leave the
 * services from Tasks 9–12 unpoliced and force a retrofit across all of them at
 * once.
 */
@ArchIgnore(reason = "No *Service classes exist until Task 9's RoleService; ArchUnit fails an empty should(). Re-enable at Task 9.")
@AnalyzeClasses(
        packages = "co.ara.onboarding",
        importOptions = ImportOption.DoNotIncludeTests.class)
class AuthorizationCoverageTest {

    /**
     * When re-enabling, services that legitimately need no gate are excluded by
     * an explicit, commented clause naming the class — never by deleting or
     * weakening the rule. The known case is TenantProvisioningService (Task 10),
     * which runs before any tenant user exists and so has no actor to authorize:
     *
     *     .and().areNotDeclaredIn(TenantProvisioningService.class)
     *
     * That clause is omitted here only because the class does not exist yet and
     * the file would not compile with it.
     *
     * There is deliberately no PermissionKeys.PLATFORM_ADMIN catch-all: a
     * permission meaning "skip the check" would be indistinguishable from a real
     * grant in the catalog, and every future ungated service would reach for it.
     * Platform-admin endpoints are secured at the HTTP layer instead
     * (Task 22 Step 9).
     */
    @ArchTest
    static final ArchRule serviceMethodsAreGated =
            methods().that().arePublic()
                     .and().areDeclaredInClassesThat().haveSimpleNameEndingWith("Service")
                     .and().areDeclaredInClassesThat().resideInAPackage("co.ara.onboarding..")
                     .should().beAnnotatedWith(RequirePermission.class)
                     .because("authorization must be central, not per-endpoint");
}
