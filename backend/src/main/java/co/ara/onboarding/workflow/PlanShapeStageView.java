package co.ara.onboarding.workflow;

import java.util.List;
import java.util.UUID;

/**
 * One portal-visible {@link Stage} inside {@link PlanShapeService#render}'s artifact.
 * Absent entirely when {@link Stage#isPortalVisible()} is {@code false} -- a hidden
 * stage drops its whole subtree, so {@code milestones} here never includes a milestone
 * whose own stage the customer cannot see, even when that milestone is itself
 * {@code portalVisible}.
 *
 * {@code key} is the stage's own {@code name}, for the same reason as {@link
 * PlanShapeMilestoneView#key()}: {@link Stage} persists no separate key column.
 */
public record PlanShapeStageView(
        UUID id,
        String key,
        List<PlanShapeMilestoneView> milestones) {}
