package co.ara.onboarding.document;

import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Task 26: resolves a {@link Case} for a portal contact's own upload, the
 * mechanism that finally closes the case-existence oracle
 * {@link DocumentService#upload}'s and {@link DocumentService#forCase}'s own
 * javadocs have documented since Task 14/15 -- both of those methods refuse
 * every {@code UserType.PORTAL} actor outright rather than route them through
 * {@link co.ara.onboarding.authz.AuthorizedQuery}, precisely because
 * {@code PortalPermissions} grants {@code document.upload}/{@code document.view}
 * at {@code Scope.ALL} (a code constant, not a catalog scope) and {@link Case}
 * has no {@link co.ara.onboarding.authz.AudienceFilter} registered (only
 * {@link Document} does) -- so {@code AuthorizationPredicateBuilder
 * .scopePredicate} would short-circuit ALL to an unconditional match with
 * nothing left to narrow it, resolving ANY case in the tenant for a portal
 * caller.
 *
 * Mirrors {@code customer.OrgUnitResolver}'s exact shape and reasoning: there
 * is no scope for {@code AuthorizedQuery} to check here in the first place, so
 * this is a plain repository lookup followed IMMEDIATELY by an explicit
 * {@code customerId} comparison -- the actual narrowing mechanism for this
 * audience, not a shortcut around one that already exists. Tenancy isolation
 * is still provided independently, by Hibernate's automatic {@code @Filter} on
 * {@code TenantScopedEntity} and by PostgreSQL row-level security, both of
 * which apply to {@code CaseRepository.findById} regardless of who calls it.
 *
 * A missing case and a customer mismatch throw the exact SAME
 * {@link NoSuchElementException} (404 either way) -- CLAUDE.md's "out-of-scope
 * records return 404, never 403" invariant, extended here to "and never
 * distinguishably from nonexistent" (spec 6.8): a portal contact must not be
 * able to tell "no such case" from "that case belongs to a different
 * customer" by response shape alone, which is exactly the oracle this class
 * exists to close.
 *
 * This class, not {@link DocumentService} itself, is the named exclusion in
 * {@code architecture.AuthorizationCoverageTest.FINDER_RULE_EXCLUSIONS}:
 * that exclusion binds at the CLASS level, so adding {@code DocumentService}
 * there would blanket-exempt every OTHER finder call in that large,
 * heavily-used service too -- a dangerous over-widening for one narrow, safe
 * need that belongs in its own small, dedicated class instead.
 */
@Component
public class PortalCaseAccess {

    private final CaseRepository cases;

    public PortalCaseAccess(CaseRepository cases) {
        this.cases = cases;
    }

    /**
     * <p>{@code @Transactional} here -- unlike {@code OrgUnitResolver}, which
     * carries none -- because every existing caller of that class is already a
     * {@code @Transactional} service method by the time it runs, so the
     * transaction (and the tenant GUC {@code tenancy.TenantTransactionBinder}
     * binds at its start) already exists. {@link co.ara.onboarding.document.PortalDocumentController}
     * is a controller, never transactional in this codebase, and calls this
     * method directly with no transaction yet open -- without this annotation,
     * {@code CaseRepository.findById} would still run (Spring Data wraps it
     * regardless), but only {@code @Transactional}/{@code @within(Transactional)}
     * join points are what {@code TenantTransactionBinder}'s own pointcut binds
     * on, so relying on that implicit wrapping alone is exactly the gap that
     * produced 403s here during this task's own development, closed once this
     * method declared its own transaction explicitly.
     *
     * @param caseId the case a portal contact is attempting to act against
     * @param contactCustomerId the ACTING contact's own {@code customerId},
     *                          resolved through {@link co.ara.onboarding.authz.PortalContactDirectory}
     *                          before this method is ever called -- never a
     *                          value taken from the request itself
     */
    @Transactional(readOnly = true)
    public Case resolveForContact(UUID caseId, UUID contactCustomerId) {
        Case c = cases.findById(caseId).orElseThrow(() -> new NoSuchElementException("Not found"));
        if (!c.getCustomerId().equals(contactCustomerId)) {
            // Deliberately the identical exception and message as the "missing
            // case" branch above -- see this class's own javadoc.
            throw new NoSuchElementException("Not found");
        }
        return c;
    }
}
