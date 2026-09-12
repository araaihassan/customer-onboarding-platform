package co.ara.onboarding.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface DocumentRepository
        extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document> {

    /** Every document on a case, for the case workspace's Documents tab. */
    List<Document> findByCaseId(UUID caseId);
}
