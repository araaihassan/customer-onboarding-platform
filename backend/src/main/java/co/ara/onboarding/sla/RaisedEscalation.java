package co.ara.onboarding.sla;

import java.util.UUID;

/** An escalation this sweep actually inserted -- the input to notification (Task 16). */
public record RaisedEscalation(UUID escalationId, EscalationSubject subjectType, UUID subjectId, UUID caseId,
                               String caseName, UUID customerId, UUID latePersonId,
                               RecipientResolver.Resolution resolution, int overdueDays) {}
