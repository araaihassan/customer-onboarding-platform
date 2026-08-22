package co.ara.onboarding.journey;

import java.util.UUID;

/** A case cannot be opened on a template with no published version to pin. */
public class TemplateNotPublishedException extends RuntimeException {

    public TemplateNotPublishedException(UUID templateId) {
        super("Workflow template " + templateId + " has no published version");
    }
}
