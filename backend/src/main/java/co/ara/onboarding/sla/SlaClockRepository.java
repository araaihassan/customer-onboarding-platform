package co.ara.onboarding.sla;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SlaClockRepository extends JpaRepository<SlaClock, UUID>, JpaSpecificationExecutor<SlaClock> {
    Optional<SlaClock> findByCaseIdAndStoppedAtIsNull(UUID caseId);
    Optional<SlaClock> findFirstByCaseIdOrderByStartedAtDesc(UUID caseId);

    /**
     * The one write of breached_at: conditional, so a clock stopped or stamped by a concurrent
     * transaction is left alone (0 rows). Never overwrites, never stamps a stopped clock.
     */
    // flush first so pending writes (the audit rows) reach the database before the clear; clear so
    // clocks already loaded in this transaction are not left holding a stale breachedAt for the
    // sweep steps that read them again.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SlaClock c set c.breachedAt = :at, c.updatedAt = :at "
            + "where c.id = :id and c.breachedAt is null and c.stoppedAt is null")
    int stampBreach(@Param("id") UUID id, @Param("at") Instant at);
}
