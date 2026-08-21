package co.ara.onboarding.journey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface CaseParticipantRepository
        extends JpaRepository<CaseParticipant, UUID>, JpaSpecificationExecutor<CaseParticipant> {

    List<CaseParticipant> findByCaseIdAndStatus(UUID caseId, ParticipantStatus status);
}
