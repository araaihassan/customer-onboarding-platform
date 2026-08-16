package co.ara.onboarding.auth;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Failed-login state for one (tenant, email), counted in PostgreSQL because Redis
 * is out of scope.
 *
 * The email is always stored lowercased — see LoginThrottleService.normalise and
 * the unique index in V10.
 */
@Entity
@Table(name = "login_attempt")
public class LoginAttempt extends TenantScopedEntity {

    @Column(nullable = false)
    private String email;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    /** Start of the current counting window; null only before the first failure. */
    @Column(name = "first_failure")
    private Instant firstFailure;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }

    public Instant getFirstFailure() { return firstFailure; }
    public void setFirstFailure(Instant firstFailure) { this.firstFailure = firstFailure; }

    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }
}
