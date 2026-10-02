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
    ASSIGNED;

    /**
     * Total breadth order ALL > DEPARTMENT > TEAM > ASSIGNED, declaration order on
     * this enum. Used only for a "does holding permission X at scope S imply
     * holding permission Y at scope S or broader" prerequisite check
     * (SignRecordImpliesMilestoneCompleteTest, spec 6.3 amendment) -- deliberately
     * NOT the comparison {@code RoleService.refuseEscalation} uses for
     * delegation, which treats DEPARTMENT and TEAM as incomparable sets rather
     * than tiers (its own javadoc: "Comparison, not hierarchy"). No reusable
     * breadth comparator existed anywhere in the codebase before this one was
     * added -- confirmed by reading refuseEscalation and
     * RoleTemplateCoverageTest, neither of which does this.
     */
    public boolean atLeastAsBroad(Scope other) {
        return this.ordinal() <= other.ordinal();
    }
}
