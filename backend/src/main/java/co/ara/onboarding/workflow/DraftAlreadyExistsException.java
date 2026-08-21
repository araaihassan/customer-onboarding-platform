package co.ara.onboarding.workflow;

import java.util.UUID;

/**
 * Exactly one DRAFT may exist per template (workflow_version_one_draft_per_template,
 * V12). Without this check the second createDraft would surface as a bare
 * DataIntegrityViolationException from the partial unique index; this names the
 * business rule explicitly instead of leaving it to the database's generic
 * violation.
 */
public class DraftAlreadyExistsException extends RuntimeException {

    public DraftAlreadyExistsException(UUID templateId) {
        super("Template " + templateId + " already has an open draft");
    }
}
