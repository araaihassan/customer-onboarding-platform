package co.ara.onboarding.authz;

/**
 * The breadth of records a grant reaches.
 *
 * Exactly four. Adding a fifth requires a PRD/QA requirement (spec 6.3) --
 * every scope must be expressible as a predicate by
 * AuthorizationPredicateBuilder (Task 13) for every resource that declares a
 * descriptor, so a new scope is a change to every descriptor in the system, not
 * a local addition here.
 */
public enum Scope {

    /** Every record in the tenant. */
    ALL,

    /** Records belonging to the actor's department. */
    DEPARTMENT,

    /** Records belonging to any team the actor is a member of. */
    TEAM,

    /** Only records the actor is individually assigned to. */
    ASSIGNED
}
