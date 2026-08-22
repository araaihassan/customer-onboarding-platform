package co.ara.onboarding.journey;

import java.util.Map;
import java.util.UUID;

public record CreateCaseRequest(UUID customerId, UUID templateId, Map<String, String> attributes) {}
