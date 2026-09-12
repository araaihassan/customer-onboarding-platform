package co.ara.onboarding.programme;

import co.ara.onboarding.journey.CaseWeight;

import java.util.List;

/**
 * A pure function over {@link CaseWeight}s -- no Spring, no database, no
 * {@code journey.CaseEngine} dependency (design spec §9.2's own cross-check:
 * every runtime mutation goes through the engine; a rollup READ never does).
 * Unit-testable directly.
 *
 * The weighting is a weighted average of each case's own {@code
 * progressPercent}, weighted by its {@code weightDays} -- NOT an unweighted
 * average of percentages. A 10-day journey at 100% and a 30-day journey at 0%
 * rolls up to 25% ((10*100 + 30*0) / (10+30)), never the unweighted 50% a
 * plain average of the two percentages would give.
 *
 * {@code weightDays == 0} across the whole set -- an empty list, or every
 * journey carrying zero estimated duration -- returns 0, never a division by
 * zero and never a default of "100% complete".
 *
 * The caller (see {@link ProgrammeService#get}) is responsible for the
 * security invariant this exists to preserve: {@code weights} must already be
 * filtered to the journeys the CURRENT ACTOR can see -- this class has no way
 * to enforce that itself, being a pure function with no notion of an actor.
 */
public final class ProgrammeRollup {

    private ProgrammeRollup() {}

    /** {@code journeysCovered} is simply {@code weights.size()} -- one entry per visible journey. */
    public record Result(int rolledUpProgressPercent, int journeysCovered) {}

    public static final Result NONE = new Result(0, 0);

    public static Result of(List<CaseWeight> weights) {
        if (weights.isEmpty()) {
            return NONE;
        }

        long totalWeightDays = 0;
        long weightedProgress = 0;
        for (CaseWeight w : weights) {
            totalWeightDays += w.weightDays();
            weightedProgress += (long) w.weightDays() * w.progressPercent();
        }

        int percent = totalWeightDays == 0
                ? 0
                : (int) Math.round(weightedProgress / (double) totalWeightDays);
        return new Result(percent, weights.size());
    }
}
