package co.ara.onboarding.task;

import jakarta.validation.constraints.NotNull;

/**
 * status is validated against the finite transition matrix TaskService.
 * guardTransition encodes (design spec §5.1) -- an edge the diagram does not
 * draw is refused as {@link IllegalTaskTransitionException}, not silently
 * accepted. reason is unused by changeStatus itself (Task 17): it exists now
 * so this request's shape does not change when Task 18 wires cancellation's
 * own NOT-NULL reason requirement on a transition into CANCELLED.
 */
public record TaskStatusRequest(@NotNull TaskStatus status, String reason) {}
