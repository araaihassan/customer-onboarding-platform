package co.ara.onboarding.sla;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SlaPauseRepository extends JpaRepository<SlaPause, UUID>, JpaSpecificationExecutor<SlaPause> {
    List<SlaPause> findByClockId(UUID clockId);
    List<SlaPause> findByClockIdIn(Collection<UUID> clockIds);
    Optional<SlaPause> findByClockIdAndReasonAndEndedAtIsNull(UUID clockId, PauseReason reason);
    List<SlaPause> findByClockIdAndEndedAtIsNull(UUID clockId);
}
