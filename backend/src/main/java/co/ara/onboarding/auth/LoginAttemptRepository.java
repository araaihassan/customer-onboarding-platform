package co.ara.onboarding.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, UUID> {

    /** Email is stored already-lowercased, so an exact match is a case-insensitive one. */
    Optional<LoginAttempt> findByTenantIdAndEmail(UUID tenantId, String email);

    void deleteByTenantIdAndEmail(UUID tenantId, String email);

    /**
     * Atomic increment. This is an UPSERT rather than a read-modify-write because
     * concurrent failed logins for one address are precisely the case that matters:
     * with read-modify-write, parallel attempts collide on the unique index, the
     * violation poisons the transaction, and those attempts are never counted — an
     * attacker firing requests in parallel would stay under the threshold
     * indefinitely.
     *
     * The window is applied inside the statement: a first_failure older than
     * windowStart starts a fresh count of 1 rather than incrementing, so a slow
     * trickle of failures never accumulates into a lockout. An expired lock is
     * cleared at the same time; an active one is preserved.
     *
     * clearAutomatically is required — callers read the row back immediately, and a
     * stale first-level cache entry would report the pre-increment count.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        INSERT INTO login_attempt
            (id, tenant_id, email, failure_count, first_failure, locked_until, created_at, updated_at)
        VALUES (:id, :tenantId, :email, 1, :now, NULL, :now, :now)
        ON CONFLICT (tenant_id, lower(email)) DO UPDATE SET
            failure_count = CASE
                WHEN login_attempt.first_failure IS NULL
                  OR login_attempt.first_failure < :windowStart THEN 1
                ELSE login_attempt.failure_count + 1 END,
            first_failure = CASE
                WHEN login_attempt.first_failure IS NULL
                  OR login_attempt.first_failure < :windowStart THEN :now
                ELSE login_attempt.first_failure END,
            locked_until = CASE
                WHEN login_attempt.locked_until IS NOT NULL
                 AND login_attempt.locked_until > :now THEN login_attempt.locked_until
                ELSE NULL END,
            updated_at = :now
        """, nativeQuery = true)
    void upsertFailure(@Param("id") UUID id,
                       @Param("tenantId") UUID tenantId,
                       @Param("email") String email,
                       @Param("now") Instant now,
                       @Param("windowStart") Instant windowStart);

    /**
     * Idempotent: applies the lock only once the threshold is reached and only if a
     * lock is not already running. Two concurrent callers both setting it is
     * harmless, which is why this needs no coordination with the increment above.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE login_attempt SET locked_until = :until, updated_at = :now
        WHERE tenant_id = :tenantId
          AND lower(email) = :email
          AND failure_count >= :threshold
          AND (locked_until IS NULL OR locked_until <= :now)
        """, nativeQuery = true)
    void applyLockIfThresholdReached(@Param("tenantId") UUID tenantId,
                                     @Param("email") String email,
                                     @Param("threshold") int threshold,
                                     @Param("until") Instant until,
                                     @Param("now") Instant now);
}
