package co.ara.onboarding.workflow;

import java.util.UUID;

/**
 * The minimal customer facts {@link CustomerTemplateService#clone} needs: the
 * id to write as {@code workflow_template.customer_id}, and the display name
 * for the {@code workflow.cloned_for_customer} audit description.
 * Deliberately narrower than {@code journey.CustomerFacts} (which carries
 * status/industry/country/ownership for branch-condition evaluation) --
 * workflow has no branch-condition use for a clone's customer, only these
 * two fields.
 */
public record CustomerSummary(UUID id, String displayName) {}
