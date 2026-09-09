package co.ara.onboarding.programme;

import java.util.List;

/**
 * {@link ProgrammeService#get}'s return type. Deliberately minimal, per this
 * task's own plan amendment: Task 14 MODIFIES this same record to add
 * {@code rolledUpProgressPercent}/{@code journeysCovered} rather than creating a
 * new type, precisely so Task 13's own tests (this task) can compile against a
 * shape Task 14 has not built yet.
 *
 * {@code journeys()} is never every {@code programme_case} link unconditionally
 * -- see {@link ProgrammeJourneyView}'s own javadoc and {@code ProgrammeScopeTest}
 * for why that would reopen the exact scope-widening shape design spec §6.3
 * exists to close.
 */
public record ProgrammeDetailView(ProgrammeView programme, List<ProgrammeJourneyView> journeys) {}
