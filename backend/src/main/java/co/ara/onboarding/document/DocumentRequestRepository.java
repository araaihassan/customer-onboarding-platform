package co.ara.onboarding.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface DocumentRequestRepository
        extends JpaRepository<DocumentRequest, UUID>, JpaSpecificationExecutor<DocumentRequest> {

    /** Every document request on a case. */
    List<DocumentRequest> findByCaseId(UUID caseId);
}
