package co.ara.onboarding.journey;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;
import java.util.UUID;

/**
 * Q18: a journey carries a human-readable name, set at creation.
 * {@code @NotBlank} -- CreateCaseDialog now collects a real one on every
 * create, so there is no caller left for a synthesized fallback to protect,
 * and production code has no way to construct a nameless case (fix round 1
 * removed the 3-arg convenience constructor this record briefly carried: it
 * let a caller skip the field silently, which is exactly backwards once the
 * dialog always sends one). {@link CaseController#create} must stay
 * {@code @Valid} for this to take effect.
 */
public record CreateCaseRequest(UUID customerId, UUID templateId, @NotBlank String name,
                                Map<String, String> attributes) {}
