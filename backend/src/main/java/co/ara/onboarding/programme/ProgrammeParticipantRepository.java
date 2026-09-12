package co.ara.onboarding.programme;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface ProgrammeParticipantRepository
        extends JpaRepository<ProgrammeParticipant, UUID>, JpaSpecificationExecutor<ProgrammeParticipant> {

    List<ProgrammeParticipant> findByProgrammeId(UUID programmeId);
}
