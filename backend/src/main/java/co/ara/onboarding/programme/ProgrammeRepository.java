package co.ara.onboarding.programme;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface ProgrammeRepository
        extends JpaRepository<Programme, UUID>, JpaSpecificationExecutor<Programme> {
}
