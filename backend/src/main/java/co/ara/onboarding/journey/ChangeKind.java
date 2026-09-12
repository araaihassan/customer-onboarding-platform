package co.ara.onboarding.journey;

/**
 * The result of comparing one milestone across two {@link PlanRevision} snapshots
 * in {@link PlanRevisionService#diff}, matched by
 * {@link PlanRevisionItem#getMilestoneDefinitionId()} -- never by milestone name
 * (mutable pre-publish) and never by the runtime milestone id (a migration can
 * repoint it to a different {@link Milestone} row entirely).
 */
public enum ChangeKind { ADDED, REMOVED, DATE_CHANGED, OWNER_CHANGED, UNCHANGED }
