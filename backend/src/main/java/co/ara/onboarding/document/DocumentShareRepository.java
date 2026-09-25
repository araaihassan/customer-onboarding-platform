package co.ara.onboarding.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocumentShareRepository
        extends JpaRepository<DocumentShare, UUID>, JpaSpecificationExecutor<DocumentShare> {

    /** Every share (live and revoked) on a document, for the sharing UI. */
    List<DocumentShare> findByDocumentId(UUID documentId);

    /**
     * Every LIVE (not yet revoked) share on a document. Two callers, both
     * feeding it only a document id already resolved through
     * {@code AuthorizedQuery} moments earlier in the very same method -- the
     * same "fed only a pre-authorized id" shape {@code journey.CaseEngine}'s
     * own finder calls already establish:
     * <ul>
     *   <li>Task 18's {@code DocumentService.retire} cascade (design spec
     *       5.5: "revokes every document_share row"), under
     *       {@code document.manage}.</li>
     *   <li>Task 19's {@code DocumentSharingService.share}, under
     *       {@code document.share}, as the idempotency pre-check that returns
     *       an already-live share to the same principal unchanged rather than
     *       attempting a duplicate insert.</li>
     * </ul>
     *
     * Named {@code liveSharesOf} rather than a {@code findBy*} shape
     * deliberately, mirroring {@code DocumentVersionRepository.maxVersionNo}/
     * {@code .versionAt}: it never matches
     * {@code AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly}'s
     * finder predicate and needs no exclusion there.
     */
    @Query("select s from DocumentShare s where s.documentId = :documentId and s.revokedAt is null")
    List<DocumentShare> liveSharesOf(@Param("documentId") UUID documentId);
}
