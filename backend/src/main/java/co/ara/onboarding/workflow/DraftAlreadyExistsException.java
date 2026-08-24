package co.ara.onboarding.workflow;

import java.util.UUID;

/**
 * Exactly one DRAFT may exist per template (workflow_version_one_draft_per_template,
 * V12). Without this check the second createDraft would surface as a bare
 * DataIntegrityViolationException from the partial unique index; this names the
 * business rule explicitly instead of leaving it to the database's generic
 * violation.
 *
 * Carries the open draft's versionId, not just the templateId: a caller that only
 * learns "one is already open" has no id to resume editing it or to discard it --
 * the product's only way out of an abandoned draft before this was a direct API
 * call nobody using the UI could make.
 */
public class DraftAlreadyExistsException extends RuntimeException {

    private final UUID versionId;

    public DraftAlreadyExistsException(UUID templateId, UUID versionId) {
        super("Template " + templateId + " already has an open draft");
        this.versionId = versionId;
    }

    public UUID versionId() {
        return versionId;
    }
}
