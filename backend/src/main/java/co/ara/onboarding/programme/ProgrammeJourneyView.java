package co.ara.onboarding.programme;

import co.ara.onboarding.journey.CaseStatus;

import java.util.UUID;

/**
 * One journey visible to the current reader inside a programme -- deliberately
 * NOT one row per {@code programme_case} link. {@link ProgrammeService#get} builds
 * this list by resolving every linked case through {@code AuthorizedQuery} under
 * {@code case.view}, so a link the current actor could not otherwise open never
 * produces a row here at all (design spec §6.3's own non-negotiable). Task 14
 * widens {@link ProgrammeDetailView}, not this record, with the duration-weighted
 * rollup fields -- {@code caseId} is the one field {@code ProgrammeScopeTest}
 * itself extracts, and {@code name}/{@code status}/{@code progressPercent} are
 * the minimum a caller needs to render a row without a second round trip; no
 * rollup-shaped field belongs here.
 */
public record ProgrammeJourneyView(UUID caseId, String name, CaseStatus status, int progressPercent) {}
