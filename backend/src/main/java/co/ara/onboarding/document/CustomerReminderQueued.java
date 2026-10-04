package co.ara.onboarding.document;

import java.util.UUID;

/** Published inside the reminder's transaction; notification turns it into an outbox row. */
public record CustomerReminderQueued(UUID requestId, UUID caseId, UUID contactId, String toAddress,
                                     String subject, String body, boolean automatic) {}
