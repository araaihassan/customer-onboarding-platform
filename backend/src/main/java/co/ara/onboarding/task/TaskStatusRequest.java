package co.ara.onboarding.task;

import jakarta.validation.constraints.NotNull;

/**
 * status is validated against the finite transition matrix TaskService.
 * guardTransition encodes (design spec §5.1) -- an edge the diagram does not
 * draw is refused as {@link IllegalTaskTransitionException}, not silently
 * accepted. reason is required (non-blank) when status is CANCELLED -- refused
 * in Java as {@link IllegalArgumentException} before task_cancel_reason_ck
 * would ever see it -- and ignored for every other transition.
 */
public record TaskStatusRequest(@NotNull TaskStatus status, String reason) {}
