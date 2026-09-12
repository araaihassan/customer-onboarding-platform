package co.ara.onboarding.programme;

import java.util.List;

/**
 * {@link ProgrammeService#get}'s return type.
 *
 * {@code journeys()} is never every {@code programme_case} link unconditionally
 * -- see {@link ProgrammeJourneyView}'s own javadoc and {@code ProgrammeScopeTest}
 * for why that would reopen the exact scope-widening shape design spec §6.3
 * exists to close.
 *
 * {@code rolledUpProgressPercent}/{@code journeysCovered} (Task 14) are {@link
 * ProgrammeRollup}'s output over the SAME already-visibility-filtered case ids
 * {@code journeys()} itself carries -- never a wider, unfiltered set. A reader
 * seeing zero journeys always sees {@code journeysCovered == 0} and {@code
 * rolledUpProgressPercent == 0} together, never a divide-by-zero and never a
 * default reading as "100% complete".
 */
public record ProgrammeDetailView(ProgrammeView programme, List<ProgrammeJourneyView> journeys,
                                  int rolledUpProgressPercent, int journeysCovered) {}
