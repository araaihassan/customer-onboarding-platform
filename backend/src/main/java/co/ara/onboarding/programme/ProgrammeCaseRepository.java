package co.ara.onboarding.programme;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface ProgrammeCaseRepository
        extends JpaRepository<ProgrammeCase, UUID>, JpaSpecificationExecutor<ProgrammeCase> {
}
