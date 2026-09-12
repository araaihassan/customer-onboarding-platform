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
 * {@code id} is the only field with a uniqueness guarantee -- it is the entity's own
 * primary key. {@code label} is the stage's own {@code name} column, for the same
 * reason as {@link PlanShapeMilestoneView#label()}: {@link Stage} persists no separate
 * key column, and neither the database nor {@code WorkflowService}'s authoring
 * validation enforces uniqueness of {@code name} within a version -- two stages can
 * legitimately both be named "Review". This is deliberately NOT called {@code key}
 * (an earlier draft of this record was): that name promises a uniqueness this field
 * cannot deliver, and this rendering is a read-only artifact, never PUT back, so there is
 * no round-trip reason to echo an id-as-string the way {@link
 * WorkflowDefinitionView.StageView#key()} does for its own, genuinely different,
 * purpose (resolving cross-references in an authoring payload). A consumer needing a
 * stable identity -- a React list key, a lookup map key -- must use {@code id}.
 */
public record PlanShapeStageView(
        UUID id,
        String label,
        List<PlanShapeMilestoneView> milestones) {}
