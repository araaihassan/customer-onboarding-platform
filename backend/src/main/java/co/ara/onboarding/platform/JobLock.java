package co.ara.onboarding.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * A transaction-scoped advisory lock keyed by (job, scope) -- spec §1.2.4. Released at commit or
 * rollback by Postgres itself, so a pooled connection can never keep it. A second instance that
 * fails to take it skips this tick.
 */
@Component
public class JobLock {
    private final JdbcTemplate jdbc;
    public JobLock(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryLock(String job, UUID scope) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtext(?), hashtext(?))",
                Boolean.class, job, scope.toString()));
    }
}
