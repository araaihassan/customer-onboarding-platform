package co.ara.onboarding.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface DocumentShareRepository
        extends JpaRepository<DocumentShare, UUID>, JpaSpecificationExecutor<DocumentShare> {

    /** Every share (live and revoked) on a document, for the sharing UI. */
    List<DocumentShare> findByDocumentId(UUID documentId);
}
