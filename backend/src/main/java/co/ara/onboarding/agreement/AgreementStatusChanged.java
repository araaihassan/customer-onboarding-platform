package co.ara.onboarding.agreement;

import java.util.UUID;

/**
 * Published, in the acting transaction, after the transition's audit record and after the
 * agreement row is flushed. {@code SIGNED} means the LAST signature only; a partial signature is
 * not announced (6B spec 1.2.7). {@code CANCELLED} names the cancelled row, not its successor.
 */
public record AgreementStatusChanged(UUID agreementId, UUID caseId, Change change, UUID actorId) {
    public enum Change { SUBMITTED, APPROVED, REJECTED, SENT, SIGNED, CANCELLED }
}
