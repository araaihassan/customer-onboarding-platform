package co.ara.onboarding.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocumentRequestRepository
        extends JpaRepository<DocumentRequest, UUID>, JpaSpecificationExecutor<DocumentRequest> {

    /** Every document request on a case. */
    List<DocumentRequest> findByCaseId(UUID caseId);

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
}
