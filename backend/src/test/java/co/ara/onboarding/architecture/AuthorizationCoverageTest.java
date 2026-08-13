package co.ara.onboarding.architecture;

import co.ara.onboarding.auth.LoginService;
import co.ara.onboarding.auth.RefreshTokenService;
import co.ara.onboarding.auth.TokenService;
import co.ara.onboarding.authz.AuthorizationService;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.provisioning.TenantProvisioningService;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameStartingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
     * Exclusions are explicit, commented clauses naming a class — never a deleted or
     * weakened rule. They fall into two categories, and both matter when judging
     * whether a NEW exclusion is legitimate.
     *
     * Runs before there is an actor to authorize:
     *   - TenantProvisioningService — no tenant user exists yet. Inventing a "may
     *     create tenants" permission would be worse than excluding it: the permission
     *     would sit in the catalog where any tenant role could be granted it. Secured
     *     at the HTTP layer instead (Task 22 Step 9).
     *   - LoginService — this is how a caller becomes authenticated, so requiring a
     *     permission would be unsatisfiable by construction.
     *   - RefreshTokenService — how a session stays authenticated. The credential it
     *     verifies is a cookie, not an authority.
     *
     * Infrastructure that the gate itself depends on, so gating it is circular:
     *   - AuthorizationService — it *is* the mechanism. Resolving the gate would
     *     require resolving the gate.
     *   - TokenService — signs and parses JWTs. No domain authority is involved, and
     *     issuing a token cannot require a token.
     *
     * Anything that does not fit one of those two descriptions should be annotated,
     * not added here. In particular a future auth/InvitationService must be gated:
     * INVITATION_SEND is a real catalogued permission, which is why these are
     * excluded per class rather than by excluding the whole auth package.
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
                     .and().areNotDeclaredIn(AuthorizationService.class)
                     .and().areNotDeclaredIn(LoginService.class)
                     .and().areNotDeclaredIn(TokenService.class)
                     .and().areNotDeclaredIn(RefreshTokenService.class)
                     .should().beAnnotatedWith(RequirePermission.class)
                     .because("authorization must be central, not per-endpoint");

    /**
     * Services must read tenant business data through AuthorizedQuery. Calling a
     * repository finder directly skips the scope predicate entirely, which is a
     * silent, total bypass of record-level authorization rather than a visible
     * error.
     *
     * The findBy* clause matters as much as the rest: a derived query like
     * contactRepository.findByCustomerId(id) carries no scope predicate either, so
     * CustomerContactService must reach contacts through AuthorizedQuery with a
     * customerId Specification rather than a derived finder.
     *
     * Scoped to co.ara.onboarding.customer.. for now; each later sub-project adds
     * its own domain package. RoleService and TenantProvisioningService are outside
     * it and unaffected — they operate on authorization metadata, not scoped
     * business records. allowEmptyShould because no customer service exists until
     * Task 20; ArchUnit fails a rule that matched nothing.
     */
    @ArchTest
    static final ArchRule servicesDoNotCallRepositoryFindersDirectly =
            noClasses().that().resideInAPackage("co.ara.onboarding.customer..")
                .and().haveSimpleNameEndingWith("Service")
                .should().callMethodWhere(
                        target(name("findAll"))
                        .or(target(name("findOne")))
                        .or(target(name("findById")))
                        .or(target(nameStartingWith("findBy"))))
                .because("reads must go through AuthorizedQuery so scope cannot be bypassed")
                .allowEmptyShould(true);
}
