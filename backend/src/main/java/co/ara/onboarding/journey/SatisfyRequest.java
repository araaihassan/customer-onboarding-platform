package co.ara.onboarding.journey;

import java.util.UUID;

/** ref/refType are the seam sub-projects 3-5 fill; both null is a plain manual check-off. */
public record SatisfyRequest(UUID ref, String refType) {}
