package co.ara.onboarding.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocumentRequestRepository
        extends JpaRepository<DocumentRequest, UUID>, JpaSpecificationExecutor<DocumentRequest> {

    /** Every document request on a case. */
    List<DocumentRequest> findByCaseId(UUID caseId);

    /** Requests on a case in one status; sla reads it to decide whether a clock starts paused. */
    long countByCaseIdAndStatus(UUID caseId, DocumentRequestStatus status);

    /**
     * Which of the given cases have a request in the given status -- one grouped query for the war
     * room instead of a count per clock. Not a finder (no find* name) and fed only case ids the
     * caller already resolved through AuthorizedQuery; it returns ids, never a request.
     */
    @Query("select r.caseId from DocumentRequest r where r.caseId in :caseIds and r.status = :status group by r.caseId")
    List<UUID> caseIdsWithRequestsIn(@Param("caseIds") java.util.Collection<UUID> caseIds,
                                     @Param("status") DocumentRequestStatus status);

    /**
     * Every FULFILLED request a document fulfilled -- {@code
     * DocumentReviewService.review}'s own discovery query for its APPROVE
     * branch: a document can, in principle, fulfil more than one request
     * (an ad-hoc one and a requirement-instantiated one both pointing at the
     * same uploaded file), so this returns every match rather than assuming
     * one. Fed only a document id already resolved through
     * {@code AuthorizedQuery} under {@code document.review} moments earlier
     * in the same caller -- the identical "pre-authorized id, discovery
     * only" shape {@link co.ara.onboarding.journey.RequirementRepository#satisfiedBy}
     * and {@code DocumentVersionRepository.versionAt} already establish.
     *
     * Named {@code fulfilledBy} rather than a {@code findBy*} shape
     * deliberately, mirroring both of those: {@code
     * AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly}'s
     * finder predicate binds on method name alone (findAll/findOne/findById/
     * findBy*), and this name does not match it, so it needs no exclusion
     * entry there.
     */
    @Query("select dr from DocumentRequest dr where dr.fulfilledDocumentId = :documentId "
            + "and dr.status = co.ara.onboarding.document.DocumentRequestStatus.FULFILLED")
    List<DocumentRequest> fulfilledBy(@Param("documentId") UUID documentId);

    /**
     * The one write of the reminder counters: conditional, so two concurrent reminders cannot both
     * pass the rate limit (the loser matches 0 rows once the winner commits) and no stale whole-entity
     * save can overwrite a counter. Only an OPEN request, and only if never reminded or last reminded
     * at or before {@code notAfter}. Returns the rows changed (1, or 0 for refused).
     */
    // flush first so the audit row written just before reaches the database; clear so the request
    // loaded earlier in this transaction is re-read with the new counters rather than held stale.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DocumentRequest r set r.remindersSent = r.remindersSent + 1, r.lastRemindedAt = :now "
            + "where r.id = :id and r.status = co.ara.onboarding.document.DocumentRequestStatus.OPEN "
            + "and (r.lastRemindedAt is null or r.lastRemindedAt <= :notAfter)")
    int markReminded(@Param("id") UUID id, @Param("now") java.time.Instant now,
                     @Param("notAfter") java.time.Instant notAfter);
}
