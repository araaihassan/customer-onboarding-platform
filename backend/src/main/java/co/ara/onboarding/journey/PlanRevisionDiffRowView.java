package co.ara.onboarding.journey;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One milestone's change between two {@link PlanRevision} snapshots, matched by
 * {@code milestoneDefinitionId} -- see {@link ChangeKind}'s own javadoc for why.
 * {@code previous*}/{@code current*} read null on the side where the milestone
 * is absent: {@link ChangeKind#ADDED} has no previous side, {@link
 * ChangeKind#REMOVED} has no current side.
 */
public record PlanRevisionDiffRowView(
        UUID milestoneDefinitionId,
        String milestoneName,
        LocalDate previousDueDate,
        LocalDate currentDueDate,
        UUID previousOwnerUserId,
        UUID currentOwnerUserId,
        ChangeKind changeKind) {}
