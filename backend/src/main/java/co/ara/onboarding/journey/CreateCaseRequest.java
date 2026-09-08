package co.ara.onboarding.journey;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
 *
 * {@code @Size(max = 160)} matches {@code onboarding_case.name}'s own
 * {@code varchar(160)} (fix round 2) -- without it, a name longer than the
 * column allows is a raw {@code DataIntegrityViolationException} (500), the
 * same overflow shape {@code V15}'s backfill already had to guard against on
 * the historical-data side with {@code left(..., 160)}.
 */
public record CreateCaseRequest(UUID customerId, UUID templateId,
                                @NotBlank @Size(max = 160) String name,
                                Map<String, String> attributes) {}
