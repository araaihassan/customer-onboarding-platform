package co.ara.onboarding.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface DocumentCaseLinkRepository
        extends JpaRepository<DocumentCaseLink, UUID>, JpaSpecificationExecutor<DocumentCaseLink> {

    /** Every case a document is linked to (live and revoked). */
    List<DocumentCaseLink> findByDocumentId(UUID documentId);

    /** Every document linked into a case, the other direction of the same share. */
    List<DocumentCaseLink> findByCaseId(UUID caseId);
}
