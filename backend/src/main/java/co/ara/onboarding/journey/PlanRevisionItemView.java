package co.ara.onboarding.journey;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The read side of one frozen {@link PlanRevisionItem} row. Every field here is
 * copied verbatim from the entity -- there is no derived field, because the whole
 * point of the snapshot is that nothing about it is computed from current state.
 */
public record PlanRevisionItemView(
        UUID id,
        UUID milestoneId,
        UUID milestoneDefinitionId,
        String stageName,
        String milestoneName,
        LocalDate dueDate,
        UUID ownerUserId,
        int estimatedDurationDays,
        boolean portalVisible,
        int sortOrder) {}
