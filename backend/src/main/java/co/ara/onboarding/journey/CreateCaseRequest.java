package co.ara.onboarding.journey;

import java.util.Map;
import java.util.UUID;

/**
 * Q18: a journey carries a human-readable name, set at creation. {@code name}
 * is nullable here rather than validated -- {@link CaseService#create} falls
 * back to the same synthetic label V15's backfill gives pre-existing rows
 * (the template's name plus the case id's own short id) when it is null or
 * blank, because CreateCaseDialog does not collect one yet (a Phase 2 UI
 * gap, not this task's to close).
 */
public record CreateCaseRequest(UUID customerId, UUID templateId, String name, Map<String, String> attributes) {

    /** Convenience overload for every caller predating Q18 -- see the class javadoc for the fallback. */
    public CreateCaseRequest(UUID customerId, UUID templateId, Map<String, String> attributes) {
        this(customerId, templateId, null, attributes);
    }
}
