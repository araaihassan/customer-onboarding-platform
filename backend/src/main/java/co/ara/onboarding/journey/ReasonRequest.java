package co.ara.onboarding.journey;

import jakarta.validation.constraints.NotBlank;

/** The shared shape behind every action that requires a mandatory reason: hold, resume's counterpart reopen, force-complete, waive. */
public record ReasonRequest(@NotBlank String reason) {}
