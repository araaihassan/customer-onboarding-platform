package co.ara.onboarding.workflow;

import java.util.UUID;

/**
 * One portal-visible {@link MilestoneDefinition} inside {@link PlanShapeService#render}'s
 * artifact -- filtered from the full graph by {@code portalVisible} on both this row and
 * its owning {@link Stage} (a hidden stage drops this milestone even when it is itself
 * visible, see {@link PlanShapeService#render}'s own javadoc).
 *
 * {@code key} is the milestone's own {@code name} -- {@code MilestoneDefinition} persists
 * no separate key column; {@link WorkflowService} resolves the client-supplied authoring
 * key against server-assigned ids and discards it once the graph is written, so name is
 * the only stable label left to key a portal client's UI off.
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
        String key,
        String description,
        int estimatedDurationDays) {}
