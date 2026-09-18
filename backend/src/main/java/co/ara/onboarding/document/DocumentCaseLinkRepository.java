package co.ara.onboarding.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocumentCaseLinkRepository
        extends JpaRepository<DocumentCaseLink, UUID>, JpaSpecificationExecutor<DocumentCaseLink> {

    /** Every case a document is linked to (live and revoked). */
    List<DocumentCaseLink> findByDocumentId(UUID documentId);

    /** Every document linked into a case, the other direction of the same share. */
    List<DocumentCaseLink> findByCaseId(UUID caseId);

    /**
     * Every LIVE (not yet revoked) link on a document. Three callers, all
     * feeding it only a document id already resolved through
     * {@code AuthorizedQuery} moments earlier in the very same method -- the
     * same "fed only a pre-authorized id" shape {@code journey.CaseEngine}'s
     * own finder calls already establish:
     * <ul>
     *   <li>Task 18's {@code DocumentService.retire} cascade (design spec
     *       5.5: "revokes every document_case_link row"), under
     *       {@code document.manage}.</li>
     *   <li>Task 20's {@code DocumentSharingService.link}, under
     *       {@code document.share}, as the idempotency pre-check that
     *       returns an existing live link to the same target case unchanged
     *       rather than attempting a duplicate insert.</li>
     *   <li>Task 20's {@code DocumentSharingService.unlink}, under
     *       {@code document.share}, to find the live link between the pair
     *       before re-resolving its own id through {@code AuthorizedQuery}.</li>
     * </ul>
     *
     * Named {@code liveLinksOf} rather than a {@code findBy*} shape
     * deliberately, mirroring {@code DocumentVersionRepository.maxVersionNo}/
     * {@code .versionAt}: it never matches
     * {@code AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly}'s
     * finder predicate and needs no exclusion there.
     */
    @Query("select l from DocumentCaseLink l where l.documentId = :documentId and l.revokedAt is null")
    List<DocumentCaseLink> liveLinksOf(@Param("documentId") UUID documentId);
}
