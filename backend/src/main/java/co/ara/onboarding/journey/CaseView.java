package co.ara.onboarding.journey;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Carries every field {@link UpdateCaseRequest} accepts -- the name, the ownership
 * triple and the attribute answers -- per CLAUDE.md's full-replace invariant. The plan's own
 * text says as much ("CaseView carries every field UpdateCaseRequest accepts...
 * which is why the ownership triple and attributes appear on both") but the record
 * shape it printed omitted {@code attributes}; added here as an executor amendment
 * rather than shipping a view a PUT round-trip could not actually reconstruct.
 * {@code name} (Q18) repeats the same lesson deliberately -- see CaseService's
 * javadoc on {@code toView}.
 */
public record CaseView(UUID id, String name, UUID customerId, UUID templateId, UUID versionId, int versionNo,
                       CaseStatus status, UUID currentStageId, String currentStageName,
                       int progressPercent, LocalDate targetCompletionDate,
                       Instant heldAt, int totalHoldDays,
                       UUID ownerUserId, UUID owningDepartmentId, UUID owningTeamId,
                       Map<String, String> attributes,
                       Instant startedAt, Instant completedAt,
                       // Non-null when the current stage is exitable but auto_advance is
                       // false: the engine computed the transition and is waiting for
                       // someone with case.advance. Always null until Task 15 fills in
                       // CaseEngine.advanceIfExitable.
                       AvailableTransitionView availableTransition) {}
