package co.ara.onboarding.document;

import java.time.Instant;
import java.util.UUID;

/**
 * The SLA clock's view of a case waiting on its customer (spec 3.2). Declared here, implemented
 * by sla, so document never imports sla. Called at every document_request.status write, inside the
 * caller's transaction: OPEN (create, DocumentInstantiation) opens; FULFILLED or WITHDRAWN closes.
 * FULFILLED means "the customer uploaded", even while internal review is pending -- review is our
 * work, not the customer's. Retiring a document or rejecting its review never changes a request's
 * status (the request stays FULFILLED), so neither path calls this.
 */
public interface CustomerWaitLifecycle {
    void requestOpened(UUID caseId, Instant at);
    void requestClosed(UUID caseId, Instant at);
}
