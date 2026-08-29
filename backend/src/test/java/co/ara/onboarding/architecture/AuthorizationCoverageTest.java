package co.ara.onboarding.architecture;

import co.ara.onboarding.auth.ActivationService;
import co.ara.onboarding.auth.LoginService;
import co.ara.onboarding.auth.MeService;
import co.ara.onboarding.auth.PasswordResetService;
import co.ara.onboarding.auth.LoginThrottleService;
import co.ara.onboarding.auth.RefreshTokenService;
import co.ara.onboarding.auth.TokenService;
import co.ara.onboarding.authz.AuthorizationService;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.provisioning.TenantProvisioningService;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.security.core.userdetails.UserDetailsService;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameEndingWith;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameStartingWith;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
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
     *   - LoginThrottleService — runs during authentication, counting attempts before
     *     any identity is established.
     *   - ActivationService, PasswordResetService — the caller holds a token and
     *     nothing else; they have no session and no roles to check.
     *
     * Returns only the caller's own record, so no permission applies:
     *   - MeService — there is no catalogued permission for knowing who you are, and
     *     inventing one would be a permission every role must hold, which is the same
     *     as no permission at all.
     *
     * Note what is NOT excluded: InvitationService. Issuing an invitation is an
     * authenticated staff action and invitation.send is a real catalogued permission,
     * so it is gated. That is exactly why issuing and accepting are separate classes
     * — one service carrying both could not be gated without exempting the half that
     * must be.
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
    /**
     * *Engine joins *Service because sub-project 2's CaseEngine is the first class
     * that orchestrates writes without being named Service. @RequirePermission binds
     * to public service methods, so a public engine outside this pattern would be an
     * ungated entry point -- the same name-shaped-guard hole CLAUDE.md records for
     * *Directory. CaseEngine is additionally package-private, so this rule is the
     * second line, not the only one.
     */
    private static final DescribedPredicate<JavaClass> GATED_CLASS_NAMES =
            simpleNameEndingWith("Service").or(simpleNameEndingWith("Engine"));

    @ArchTest
    static final ArchRule serviceMethodsAreGated =
            methods().that().arePublic()
                     .and().areDeclaredInClassesThat(GATED_CLASS_NAMES)
                     .and().areDeclaredInClassesThat().resideInAPackage("co.ara.onboarding..")
                     .and().areNotDeclaredIn(TenantProvisioningService.class)
                     .and().areNotDeclaredIn(AuthorizationService.class)
                     .and().areNotDeclaredIn(LoginService.class)
                     .and().areNotDeclaredIn(TokenService.class)
                     .and().areNotDeclaredIn(RefreshTokenService.class)
                     .and().areNotDeclaredIn(LoginThrottleService.class)
                     .and().areNotDeclaredIn(ActivationService.class)
                     .and().areNotDeclaredIn(PasswordResetService.class)
                     .and().areNotDeclaredIn(MeService.class)
                     // Spring Security SPI, not a domain service: invoked by the filter
                     // chain during authentication, never reachable from a controller.
                     .and().areDeclaredInClassesThat().areNotAssignableTo(UserDetailsService.class)
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
     * Covers customer and identity — the two domain packages whose reads are
     * record-scoped. identity joined in Task 21, when UserAdminService made user.view
     * scoping real. Each later sub-project adds its own domain package here.
     * TenantProvisioningService is outside it and unaffected — it runs before any
     * tenant user exists.
     *
     * auth joined at the close of sub-project 1, and the reason is the whole point
     * of this rule. UserInvitationService sat in auth, was gated on user.manage, and
     * read its target with users.findById — so a DEPARTMENT-scoped holder could mint
     * and mail an activation invitation for any user in the tenant. Nothing flagged
     * it, because auth was outside the packages this rule named. That is the third
     * instance of one seam: any service method that takes a foreign id from the URL
     * or body and writes without resolving it through AuthorizedQuery is a scope
     * bypass, because @RequirePermission cannot see arguments. Widening turns that
     * from a habit into a guard.
     *
     * The auth exclusions below are the SAME classes, for the same two reasons,
     * already excluded from serviceMethodsAreGated above — not a second, looser
     * list. Each resolves its subject from a bearer credential (a token hash, an
     * email presented at login) or from the caller's own principal, never from a
     * caller-supplied id, and each runs with no actor whose scope AuthorizedQuery
     * could apply. Adding a class here is only legitimate on that showing. What
     * remains covered in auth is exactly the category that escaped: the gated,
     * authenticated services — InvitationService and UserInvitationService.
     *
     * authz is deliberately NOT included. Its only *Service is RoleService, and
     * role, role_grant and user_role are authorization metadata rather than scoped
     * business records: role.view and role.manage are ALL-only in the catalog and
     * Role has no ResourceAuthorizationDescriptor, so AuthorizedQuery could not be
     * used there even in principle — DescriptorRegistry.forEntity has nothing to
     * return. Naming the package and then excluding its only service would leave
     * the clause binding to nothing, which is a rule that looks like coverage and
     * is not. If sub-project 2 gives roles a record-level scope, that changes.
     *
     * Live and non-vacuous as of Task 20, which added the first customer services;
     * the allowEmptyShould it carried until then is gone.
     *
     * The rule was name-shaped and bound only to *Service, so *Directory classes were
     * invisible to it. CLAUDE.md flagged the consequence: "a future *Directory taking
     * a foreign id would be unguarded in exactly the way auth was." Task 10 writes
     * exactly such a class (CustomerDirectory's implementation, taking a customer id
     * from a request body), so the rule widens before that class exists.
     *
     * Two exclusions, both category one -- runs before there is an actor to
     * authorize:
     *   - IdentityActorDirectory: supplies the department and teams that scope
     *     resolution itself needs. Gating it would require resolving the gate.
     *   - UserRoleDirectory: reads a user's role rows for the same resolution.
     */
    @ArchTest
    static final ArchRule servicesDoNotCallRepositoryFindersDirectly =
            noClasses().that()
                .haveSimpleNameEndingWith("Service").or().haveSimpleNameEndingWith("Directory")
                .and().resideInAnyPackage("co.ara.onboarding.customer..",
                                          "co.ara.onboarding.identity..",
                                          "co.ara.onboarding.auth..",
                                          "co.ara.onboarding.workflow..",
                                          "co.ara.onboarding.journey..")
                // Same exclusion: authentication runs with no actor and platform_admin
                // is not tenant-scoped, so there is no scope for AuthorizedQuery to
                // apply -- it could not be used here even in principle.
                .and().areNotAssignableTo(UserDetailsService.class)
                // Runs before there is an actor to scope against, or resolves the
                // caller's own record only. Identical list and identical reasoning to
                // serviceMethodsAreGated's exclusions.
                .and().areNotAssignableTo(LoginService.class)
                .and().areNotAssignableTo(LoginThrottleService.class)
                .and().areNotAssignableTo(RefreshTokenService.class)
                .and().areNotAssignableTo(ActivationService.class)
                .and().areNotAssignableTo(PasswordResetService.class)
                .and().areNotAssignableTo(MeService.class)
                // Three exclusions, category one: runs before there is an actor
                // to authorize. IdentityActorDirectory and UserRoleDirectory supply
                // the department and teams that scope resolution itself needs.
                // CustomerService resolves department and team ids through plain
                // repository lookups: the Hibernate filter and RLS provide tenancy
                // isolation, and no permission predicates exist for these reads (only
                // DEPARTMENT_MANAGE/TEAM_MANAGE, both ALL-only administrative
                // permissions, not view permissions). Excluded by class rather than
                // by name pattern: a second class that happens to end in "Directory"
                // would not inherit the exemption.
                .and().doNotHaveFullyQualifiedName(
                        "co.ara.onboarding.identity.IdentityActorDirectory")
                .and().doNotHaveFullyQualifiedName(
                        "co.ara.onboarding.authz.UserRoleDirectory")
                .and().doNotHaveFullyQualifiedName(
                        "co.ara.onboarding.customer.CustomerService")
                .should().callMethodWhere(
                        (target(name("findAll"))
                         .or(target(name("findOne")))
                         .or(target(name("findById")))
                         .or(target(nameStartingWith("findBy"))))
                        // AuthorizedQuery's own methods are named findAll and getById,
                        // so a name-only predicate flags the sanctioned wrapper exactly
                        // as loudly as the bypass it exists to prevent. Excluding it by
                        // owner states the rule's real intent: reach finders THROUGH
                        // AuthorizedQuery, never around it.
                        .and(not(target(owner(nameEndingWith("AuthorizedQuery")))))
                        // Task 21's one documented carve-out from the read invariant
                        // itself, not merely from this name-shaped rule: journey.
                        // TimelineService reaches audit_event through
                        // audit.AuditQuery.findForResource, which resolves the CASE
                        // through AuthorizedQuery first -- that resolution IS the
                        // authorization -- then reads by exact (resource_type,
                        // resource_id) rather than a scope-shaped query.
                        // AuditEventDescriptor scopes by ACTOR, the wrong axis for a
                        // case's shared history (see TimelineService's own javadoc),
                        // which is why this reaches AuditQuery instead of
                        // AuthorizedQuery like every other read in the codebase.
                        .and(not(target(owner(nameEndingWith("AuditQuery"))))))
                .because("reads must go through AuthorizedQuery so scope cannot be bypassed -- "
                        + "except journey.TimelineService's one documented carve-out through audit.AuditQuery");
}
