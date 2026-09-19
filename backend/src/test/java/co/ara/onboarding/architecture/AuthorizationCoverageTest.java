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
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetailsService;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;

import java.util.List;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static org.assertj.core.api.Assertions.assertThat;
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
     * Rebound as of sub-project 3A Task 2 from a name-shaped rule
     * (haveSimpleNameEndingWith("Service").or(haveSimpleNameEndingWith("Directory")))
     * to a UNION of that name shape with a new injection shape: every class in the
     * covered packages that either is named *Service/*Directory OR injects a
     * repository is covered, whatever it is named. The old, purely name-shaped rule
     * let three task classes -- TaskInstantiation, TaskDirectoryAdapter,
     * TaskLifecycleAdapter -- fall outside it purely by being named something
     * other than *Service/*Directory, which made their exemption invisible to
     * a reviewer of the guard itself (TaskInstantiation's own javadoc used to
     * say outright that this was the point of its name). It also made
     * customer.OrgUnitResolver's exclusion clause a no-op, since that name
     * matches neither suffix either -- it was already excluded by not
     * matching in the first place. See FINDER_RULE_EXCLUSIONS below for the
     * real, named exclusion list this rebind replaces both dodges with.
     *
     * Deliberately a union, not a replacement: a covered-package *Service or
     * *Directory class that reaches a finder on a repository it does NOT hold as
     * a field (passed as a parameter, obtained from another object, etc.) would be
     * invisible to injectsARepository() alone, narrowing coverage in exactly the
     * dimension the name-shaped half used to catch. Measured empirically at the
     * time of this rebind: exactly one class, auth.TokenService, is *Service-named
     * but injects no Repository field and makes no find* call at all -- so nothing
     * is actually lost by the injection-only version today, but the union is kept
     * anyway so a future *Service that reaches a finder through an injected
     * collaborator rather than its own field stays caught.
     */
    private static DescribedPredicate<JavaClass> injectsARepository() {
        return new DescribedPredicate<>("injects a repository") {
            @Override
            public boolean test(JavaClass c) {
                return c.getAllFields().stream()
                        .anyMatch(f -> f.getRawType().getSimpleName().endsWith("Repository"));
            }
        };
    }

    private static DescribedPredicate<JavaClass> haveFullyQualifiedNameIn(List<String> names) {
        return new DescribedPredicate<>("fully qualified name in the exclusion list") {
            @Override
            public boolean test(JavaClass c) {
                return names.contains(c.getFullName());
            }
        };
    }

    /**
     * Every class in the covered packages that INJECTS a repository is covered,
     * whatever it is named. The previous rule bound to a "Service"/"Directory" name
     * suffix, which three task classes were deliberately named to fall outside --
     * making their exemption invisible to a reviewer of the guard itself -- and which
     * made customer.OrgUnitResolver's exclusion a no-op, since that name matches
     * neither suffix. An exemption must be a line in this list, not a naming choice.
     *
     * Rebinding this rule (sub-project 3A Task 2) surfaced four MORE classes the
     * name-shaped rule was equally blind to, none of them anticipated by the plan --
     * each verified individually below rather than added reflexively:
     *   - auth.PendingInvitationRevoker and customer.LinkedPortalUserEmailSync were,
     *     like the three task classes, deliberately named to dodge the OLD rule --
     *     both classes' own (now-corrected) javadocs said so outright.
     *   - identity.PlatformAdminBootstrap was invisible for a different reason: it
     *     never matched the name suffix in the first place, same as OrgUnitResolver.
     *   - journey.CaseEngine was invisible because the OLD rule only ever bound to
     *     *Service/*Directory, never *Engine -- CLAUDE.md already documents ONE of
     *     its finder calls (CaseRepository.lockById) escaping this exact rule for
     *     this exact reason; rebinding surfaces that its OTHER internal finder
     *     calls (StageRepository, MilestoneDefinitionRepository, ApprovalRepository,
     *     etc.) were equally invisible, not just the one CLAUDE.md already named.
     *
     * authz.UserRoleDirectory does NOT belong here and was removed from this list
     * during the Task 2 fix round: this rule's covered packages are customer..,
     * identity.., auth.., workflow.., journey.., task.. -- authz is not one of them
     * (see the deliberate reasoning above, "authz is deliberately NOT included"),
     * so a class in authz can never be selected by this rule regardless of what it
     * injects or calls. Listing it here excluded nothing -- the exact no-op-exclusion
     * defect this rebind exists to eliminate, the same shape customer.OrgUnitResolver
     * had before this task. identity.IdentityActorDirectory is the real, live
     * exclusion for the "supplies the department/team scope that resolution itself
     * needs" reason -- authz.UserRoleDirectory's own analogous reasoning (it answers
     * a question about users for an already-gated caller, UserAdminService) simply
     * never needed to be on this list in the first place.
     */
    static final List<String> FINDER_RULE_EXCLUSIONS = List.of(
            // Runs before there is an actor to authorize -- supplies the department
            // and team scope that resolution itself needs.
            "co.ara.onboarding.identity.IdentityActorDirectory",
            // Resolves department and team ids through plain repository lookups
            // because no DEPARTMENT_VIEW or TEAM_VIEW permission exists to scope
            // against -- only the ALL-only DEPARTMENT_MANAGE and TEAM_MANAGE.
            // (The "RLS handles it" half of this exclusion's original justification
            // is deleted: RLS is tenant isolation, not record scope, and restating
            // it is the argument this rule exists to reject.)
            "co.ara.onboarding.customer.OrgUnitResolver",
            // Runs at application startup via ApplicationRunner, before any tenant or
            // actor exists at all -- the identity-package twin of
            // provisioning.TenantProvisioningService's own exclusion from
            // serviceMethodsAreGated, for the same reason. Its one finder call is
            // keyed on a configuration value (app.platform-admin.email), never a
            // caller-supplied id.
            "co.ara.onboarding.identity.PlatformAdminBootstrap",
            // Fed only pre-authorized ids by a caller that already resolved them
            // through AuthorizedQuery -- the CaseEngine/lockById precedent.
            //
            // TaskDirectoryAdapter is deliberately NOT on this list any more
            // (sub-project 3A Task 5): it bypassed AuthorizedQuery on the
            // reasoning that CaseService.roadmap() already resolved every
            // milestone id under CASE_VIEW before summaryFor saw it -- but
            // CASE_VIEW and TASK_VIEW are different permissions held at
            // different scopes, so an ASSIGNED-scoped task.view holder's
            // count leaked tasks assigned to somebody else. Its summaryFor
            // now calls authorizedQuery.findAll under TASK_VIEW, the same
            // sanctioned wrapper every other class here reaches through, so
            // it needs no exclusion at all.
            "co.ara.onboarding.task.TaskInstantiation",
            "co.ara.onboarding.task.TaskLifecycleAdapter",
            // Both ids PendingInvitationRevoker acts on are resolved through
            // AuthorizedQuery by its only callers (UserInvitationService,
            // auth.InvitationService) before either method runs -- never a fresh
            // caller-supplied value.
            "co.ara.onboarding.auth.PendingInvitationRevoker",
            // Every id LinkedPortalUserEmailSync acts on is resolved off a
            // CustomerContact that CustomerContactService's own AuthorizedQuery call
            // already authorized under CONTACT_MANAGE earlier in the same method.
            "co.ara.onboarding.customer.LinkedPortalUserEmailSync",
            // Package-private and unreachable from outside journey (CLAUDE.md's own
            // invariant: CaseEngine.reconcile, under CaseRepository.lockById's row
            // lock, is the only path to a runtime mutation). Every FINDER call this
            // rule can see inside CaseEngine is keyed off a field on a Case or Stage
            // object its callers already resolved through AuthorizedQuery/lockById --
            // reconcile(Case) and pendingTransition(Case), its two public entry
            // points, both take the domain object, never a raw id -- so there is no
            // fresh, caller-supplied id here for AuthorizedQuery to protect against.
            //
            // CaseEngine ALSO has a third, package-visible method,
            // lockAndLoad(UUID caseId), which does take a raw id and calls
            // cases.lockById(caseId) -- but lockById/lockAndLoad never matches this
            // rule's own target predicate (findAll|findOne|findById|findBy*), so it
            // is outside what this exclusion is even needed for. It is safe today,
            // but only as CALLER DISCIPLINE, not a structural guarantee this rule (or
            // any other) enforces: every one of its eleven call sites
            // (CaseService.java:400-401,426-427,447-448; MigrationService.java
            // :126-127; ApprovalService.java:79-86,116-128; MilestoneService.java
            // :130-132,184; RequirementService.java:82,120) authorizes the case id
            // through AuthorizedQuery before ever calling lockAndLoad, but nothing
            // stops a future caller from passing an unauthorized id straight to it.
            "co.ara.onboarding.journey.CaseEngine",
            // Sub-project 4 Task 24: DocumentInstantiation is called inside
            // CaseService.create's own transaction (via DocumentRequestLifecycleAdapter),
            // on a caseId that method just created and fully controls -- the identical
            // shape task.TaskInstantiation's own entry above already documents, DOCUMENT
            // rather than TASK.
            "co.ara.onboarding.document.DocumentInstantiation",
            // Sub-project 4 Task 26: the customer.OrgUnitResolver shape, applied to
            // Case for a portal actor. PortalPermissions grants document.upload/
            // document.view at Scope.ALL and Case has no AudienceFilter registered,
            // so resolving a caseId under either permission through AuthorizedQuery
            // for a portal actor would resolve ANY case in the tenant -- there is no
            // scope for AuthorizedQuery to check here in the first place. This class
            // is the actual narrowing mechanism for this audience (a plain findById
            // followed IMMEDIATELY by an explicit customerId comparison), not a
            // bypass of one that already exists -- see its own javadoc. Named here
            // rather than excluding DocumentService itself, which would
            // blanket-exempt every OTHER finder call that large service makes.
            "co.ara.onboarding.document.PortalCaseAccess");

    @ArchTest
    static final ArchRule servicesDoNotCallRepositoryFindersDirectly =
            noClasses().that()
                .resideInAnyPackage("co.ara.onboarding.customer..",
                                          "co.ara.onboarding.identity..",
                                          "co.ara.onboarding.auth..",
                                          "co.ara.onboarding.workflow..",
                                          "co.ara.onboarding.journey..",
                                          // Task 16: TaskService's own case_id/milestoneId/
                                          // requirementId/assigneeId resolution is exactly the
                                          // shape this rule exists to catch -- added before
                                          // TaskService itself was written, not retrofitted.
                                          "co.ara.onboarding.task..",
                                          // Sub-project 3A Task 12: ProgrammeService's own
                                          // customerId resolution is exactly this shape --
                                          // added before ProgrammeService itself was written,
                                          // not retrofitted, same as task.. above.
                                          "co.ara.onboarding.programme..",
                                          // Sub-project 4 Task 14: DocumentService is the
                                          // first *Service in this module. Added in the same
                                          // commit that introduces it, before any finder call
                                          // exists to catch -- the injection-shaped half of
                                          // this rule covers it automatically, with no
                                          // exclusion needed, exactly as this rule's own
                                          // javadoc promises.
                                          "co.ara.onboarding.document..")
                // Union, not replace: a covered-package *Service/*Directory class that
                // reaches a finder on a repository it does NOT hold as a field (passed
                // as a parameter, obtained from another object, etc.) would be
                // unselected by injectsARepository() alone -- narrowing coverage in
                // exactly the dimension the old name-shaped rule used to catch. Keeping
                // the name-shape half alongside the new injection-shape half closes the
                // *Directory/*Engine/other-name blind spot without reopening this one.
                .and(injectsARepository()
                        .or(simpleNameEndingWith("Service"))
                        .or(simpleNameEndingWith("Directory")))
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
                // Every exemption from the injection-shaped rule is a named line in
                // FINDER_RULE_EXCLUSIONS above, not a naming choice that happens to
                // dodge a suffix match.
                .and(not(haveFullyQualifiedNameIn(FINDER_RULE_EXCLUSIONS)))
                .should().callMethodWhere(
                        // This predicate binds on NAME alone -- findAll/findOne/findById/
                        // findBy* -- which is exactly why a repository method spelled
                        // differently never reaches it, exemption or not. Five deliberate,
                        // reviewed instances of that today, recorded HERE (beside the
                        // predicate a reviewer of THIS guard actually reads) rather than
                        // only in each repository's own file:
                        //   - document.DocumentVersionRepository.maxVersionNo(documentId) is
                        //     safe unconditionally: it returns an aggregate int, not a scoped
                        //     entity, so there is no row for it to leak regardless of who
                        //     calls it or with what id.
                        //   - document.DocumentVersionRepository.versionAt(documentId,
                        //     versionNo) is NOT unconditionally safe the same way -- it
                        //     returns a real DocumentVersion, a scoped entity, and unlike
                        //     maxVersionNo the only thing stopping it from matching this
                        //     predicate is its name not starting with "findBy". It is safe
                        //     today only because its one caller,
                        //     document.DocumentContentService.open, calls it exclusively
                        //     with a documentId already resolved through AuthorizedQuery
                        //     under document.view moments earlier in the same method -- the
                        //     same "fed only a pre-authorized id" shape FINDER_RULE_EXCLUSIONS
                        //     already documents for journey.CaseEngine's own finder calls.
                        //   - journey.RequirementRepository.satisfiedBy(ref, refType), added
                        //     by Task 18: discovery only. journey.RequirementService.reopen
                        //     re-resolves every match through AuthorizedQuery before mutating
                        //     anything, and document.DocumentService.retire calls it directly
                        //     first (fed only a document id already resolved through
                        //     AuthorizedQuery under document.manage moments earlier in the
                        //     same method) purely to decide WHETHER to call the gated reopen
                        //     at all.
                        //   - document.DocumentShareRepository.liveSharesOf(documentId), added
                        //     by Task 18 for the retire() cascade and given a second caller by
                        //     Task 19: document.DocumentService.retire (fed only a document id
                        //     already resolved through AuthorizedQuery under document.manage
                        //     moments earlier in the same method, to revoke every LIVE share)
                        //     and document.DocumentSharingService.share (fed only a document id
                        //     already resolved through AuthorizedQuery under document.share
                        //     moments earlier in the same method, as an idempotency pre-check
                        //     before inserting a new one) -- both callers feed it only a
                        //     pre-authorized id, the same "fed only a pre-authorized id" shape
                        //     as the rest of this list.
                        //   - document.DocumentCaseLinkRepository.liveLinksOf(documentId), added
                        //     by Task 18 for the retire() cascade and given two more callers by
                        //     Task 20: document.DocumentService.retire (fed only a document id
                        //     already resolved through AuthorizedQuery under document.manage
                        //     moments earlier in the same method, then used to revoke every LIVE
                        //     row keyed off that one already-authorized document) and
                        //     document.DocumentSharingService.link/unlink (both fed only a
                        //     document id already resolved through AuthorizedQuery under
                        //     document.share moments earlier in the same method -- link as an
                        //     idempotency pre-check before inserting a new one, unlink to find
                        //     the live link between the pair before re-resolving its own id
                        //     through AuthorizedQuery) -- every caller feeds it only a
                        //     pre-authorized id, the same "fed only a pre-authorized id" shape
                        //     as the rest of this list.
                        //     None of these five is added to FINDER_RULE_EXCLUSIONS: that list
                        //     blanket-exempts every finder call a listed CLASS makes, present
                        //     and future, which is too wide a grant for a safety argument that
                        //     applies to this one METHOD's one caller -- a comment here is the
                        //     right shape, not a rule change.
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

    @Test
    void finderRuleBindsToRepositoryInjectionNotClassName() {
        // TaskInstantiation and TaskLifecycleAdapter inject repositories and call
        // finders directly. Under the name-shaped rule they were invisible. Under
        // the rebound rule each must appear as a NAMED exclusion, never as a class
        // the rule silently fails to see. TaskDirectoryAdapter is deliberately NOT
        // asserted here any more (sub-project 3A Task 5): it no longer calls a
        // finder outside AuthorizedQuery, so it carries no exclusion at all --
        // see FINDER_RULE_EXCLUSIONS' own comment for why. DocumentInstantiation
        // (sub-project 4 Task 24) is the same shape as TaskInstantiation.
        // DocumentRequestLifecycleAdapter is deliberately NOT asserted here, same
        // reasoning as TaskDirectoryAdapter's own omission: it injects no
        // repository of its own (only DocumentInstantiation), so it never matches
        // injectsARepository() and needs no exclusion at all.
        assertThat(FINDER_RULE_EXCLUSIONS)
                .contains("co.ara.onboarding.task.TaskInstantiation",
                          "co.ara.onboarding.task.TaskLifecycleAdapter",
                          "co.ara.onboarding.document.DocumentInstantiation",
                          "co.ara.onboarding.document.PortalCaseAccess")
                .doesNotContain("co.ara.onboarding.task.TaskDirectoryAdapter");
    }
}
