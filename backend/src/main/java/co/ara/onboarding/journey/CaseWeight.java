package co.ara.onboarding.journey;

import java.util.UUID;

/**
 * One case's contribution to a duration-weighted rollup (sub-project 3A, QA
 * Q20). {@code progressPercent} is the case's own already-computed, already-
 * stored progress ({@link Case#getProgressPercent()} -- the same "one number
 * for every audience" value {@link CaseEngine} writes, never recomputed here.
 * {@code weightDays} is the sum of {@code estimated_duration_days} over the
 * case's non-{@code SKIPPED} milestones -- the identical weighting
 * {@link CaseEngine#progressOf} applies within a single case, reused here to
 * weight ACROSS cases so a programme's rollup and a case's own progress bar
 * never disagree about what counts.
 *
 * Produced only by {@link CaseWeightReader#weightsFor}, which resolves every
 * case id through {@code AuthorizedQuery} first -- a {@code CaseWeight} never
 * exists for a case the caller could not otherwise see.
 */
public record CaseWeight(UUID caseId, int progressPercent, int weightDays) {}
