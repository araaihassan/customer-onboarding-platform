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
     * Every LIVE (not yet revoked) link on a document -- Task 18's
     * {@code DocumentService.retire} cascade (design spec 5.5: "revokes every
     * document_case_link row"), fed only a document id already resolved
     * through {@code AuthorizedQuery} under {@code document.manage} moments
     * earlier in the very same method, the same "fed only a pre-authorized
     * id" shape {@code journey.CaseEngine}'s own finder calls already
     * establish.
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
