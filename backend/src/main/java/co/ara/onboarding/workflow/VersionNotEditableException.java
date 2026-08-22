package co.ara.onboarding.workflow;

import java.util.UUID;

/**
 * Once PUBLISHED a version is frozen at the storage layer (V12's
 * refuse_published_version_change / refuse_published_child_write triggers). This is
 * the service-layer half of that same rule, raised before any write is attempted
 * rather than surfacing as a raw SQL exception from the trigger.
 */
public class VersionNotEditableException extends RuntimeException {

    public VersionNotEditableException(UUID versionId) {
        super("Workflow version " + versionId + " is not a draft and cannot be modified");
    }
}
