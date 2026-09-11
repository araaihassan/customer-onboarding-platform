package co.ara.onboarding.journey;

import java.util.List;

/**
 * The full row-by-row comparison of one {@link PlanRevision} snapshot against
 * another, computed server-side by {@link PlanRevisionService#diff} -- never
 * recomputed per client, so two clients reading the same pair of revisions
 * cannot disagree about what changed.
 */
public record PlanRevisionDiffView(List<PlanRevisionDiffRowView> rows) {}
