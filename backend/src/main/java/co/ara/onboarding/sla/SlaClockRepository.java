package co.ara.onboarding.sla;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;
import java.util.UUID;

public interface SlaClockRepository extends JpaRepository<SlaClock, UUID>, JpaSpecificationExecutor<SlaClock> {
    Optional<SlaClock> findByCaseIdAndStoppedAtIsNull(UUID caseId);
    Optional<SlaClock> findFirstByCaseIdOrderByStartedAtDesc(UUID caseId);
}
