package co.ara.onboarding.journey;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * blockedByMilestoneNames is empty except when status is BLOCKED -- CaseEngine
 * computes the status for its own transition logic but discards which
 * dependency was unmet, so this recomputes the same check read-only for
 * display: "blocked" without saying what it is blocked BY is colour carrying
 * the whole signal (review finding 10).
 *
 * portalVisible was missing entirely until sub-project 3A's own task-33 e2e
 * proof surfaced it: Task 32 added the authoring-time toggle
 * (MilestoneEditor's Switch) and MilestoneRow.tsx's "Internal" badge against
 * this exact field, but never added it here -- a frontend-only task whose own
 * assumption (that the roadmap already carried it) was never checked against
 * the real generated type. `npx vitest` never caught it (esbuild/swc
 * transpile, no type-check); `next build`'s real `tsc` pass does, which is
 * what actually surfaced this. Copied straight from
 * MilestoneDefinition.isPortalVisible() -- the authoring-time flag.
 * StageRoadmapView carries no equivalent field: nothing reads a
 * stage-level hide from the roadmap itself, only from the authoring-time
 * definition (the Plan tab's own flattenPlanMilestones, which already reads
 * Stage.portalVisible through useDefinition) -- so this milestone-level
 * field is the only one this bug required.
 */
public record MilestoneRoadmapView(UUID id, String name, MilestoneStatus status,
                                   UUID ownerUserId, LocalDate dueDate, int progressPercent,
                                   List<String> blockedByMilestoneNames,
                                   List<RequirementRoadmapView> requirements,
                                   TaskSummary taskSummary, boolean portalVisible) {}
