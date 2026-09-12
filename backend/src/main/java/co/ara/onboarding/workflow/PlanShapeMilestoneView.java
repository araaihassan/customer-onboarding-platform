package co.ara.onboarding.workflow;

import java.util.UUID;

/**
 * One portal-visible {@link MilestoneDefinition} inside {@link PlanShapeService#render}'s
 * artifact -- filtered from the full graph by {@code portalVisible} on both this row and
 * its owning {@link Stage} (a hidden stage drops this milestone even when it is itself
 * visible, see {@link PlanShapeService#render}'s own javadoc).
 *
 * {@code id} is the only field with a uniqueness guarantee -- it is the entity's own
 * primary key, stable for the life of this {@link WorkflowVersion} (a new version --
 * created by cloning, refresh, or authoring a fresh draft -- gets entirely new rows and
 * so entirely new ids, but that is a version boundary a customer-facing "which milestone
 * is this, right now" identity never needs to survive).
 *
 * {@code label} is the milestone's own {@code name} -- {@code MilestoneDefinition}
 * persists no separate key column; {@code WorkflowService} resolves the client-supplied
 * authoring key against server-assigned ids and discards it once the graph is written.
 * This field is deliberately NOT called {@code key} (an earlier draft of this record
 * was): neither the database nor {@code WorkflowService}'s authoring validation enforces
 * uniqueness of {@code name} within a version or a stage -- a builder user can
 * legitimately author two milestones sharing a name, so a field named {@code key} would
 * promise a uniqueness it cannot deliver. {@code label} is display-only, may collide, and
 * a consumer needing a stable identity (a React list key, a lookup map key) must use
 * {@code id} instead. This is a genuinely different situation from {@link
 * WorkflowDefinitionView.MilestoneView#key()}, which echoes the id as a string for a real
 * round-trip reason (resolving {@code dependsOnMilestoneKeys}-shaped cross-references in
 * an authoring PUT) that does not apply here: this rendering is read-only and never PUT
 * back.
 *
 * Deliberately carries {@code estimatedDurationDays} (the SHAPE's duration, this gate's
 * subject) and nothing else runtime-shaped: no {@code dueDate}, no {@code ownerUserId}.
 * Both are columns on a running {@code journey.Milestone}, computed only once a case
 * exists against a pinned version -- gate 1 approves a shape and a duration, not a
 * schedule, and a rendering that showed either would promise the customer a date or an
 * assignment gate 2 (the per-journey schedule approval) is what actually commits to.
 */
public record PlanShapeMilestoneView(
        UUID id,
        String label,
        String description,
        int estimatedDurationDays) {}
