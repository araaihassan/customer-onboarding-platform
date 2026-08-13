package co.ara.onboarding.architecture;

import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.provisioning.TenantProvisioningService;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * Every public method on a *Service that a controller can reach must carry an
 * explicit permission gate (spec 6.10).
 *
 * Live as of Task 9, whose RoleService is the first *Service class in the
 * codebase. It was @ArchIgnore'd for Tasks 7–8 because ArchUnit fails a rule
 * whose should() matched nothing rather than passing it vacuously, so the rule
 * had nothing to bind to and would have reported "failed to check any classes" —
 * noise, not a finding.
 *
 * The annotation this enforces is declarative only until Task 13's
 * PermissionGateAspect. That is the point of enforcing it now anyway: services
 * declare their keys as they are written, and Task 13 makes the existing
 * declarations enforceable rather than requiring a retrofit across every service
 * added in Tasks 9–12.
 */
@AnalyzeClasses(
        packages = "co.ara.onboarding",
        importOptions = ImportOption.DoNotIncludeTests.class)
class AuthorizationCoverageTest {

    /**
     * Services that legitimately need no gate are excluded by an explicit,
     * commented clause naming the class — never by deleting or weakening the rule.
     * The one case is TenantProvisioningService, which runs before any tenant user
     * exists and so has no actor to authorize. Inventing a "may create tenants"
     * permission would be worse than excluding it: the permission would sit in the
     * catalog where any tenant role could be granted it. That endpoint is secured
     * at the HTTP layer instead (Task 22 Step 9).
     *
     * Excluding by class rather than by name pattern is deliberate — a second
     * service that happens to end in "ProvisioningService" would not inherit the
     * exemption.
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
                     .and().areNotDeclaredIn(TenantProvisioningService.class)
                     .should().beAnnotatedWith(RequirePermission.class)
                     .because("authorization must be central, not per-endpoint");
}
