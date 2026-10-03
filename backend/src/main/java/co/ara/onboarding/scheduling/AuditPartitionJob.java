package co.ara.onboarding.scheduling;

import co.ara.onboarding.platform.JobLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Spec 6.4: keeps three months of audit_event partitions ahead. Global, not per tenant: no tenant
 * is bound and no actor exists, because the SQL function it calls touches no tenant row.
 */
@Component
public class AuditPartitionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditPartitionJob.class);
    private static final UUID GLOBAL = new UUID(0L, 0L);
    private static final int MONTHS_AHEAD = 3;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JobLock lock;

    public AuditPartitionJob(JdbcTemplate jdbc, PlatformTransactionManager txManager, JobLock lock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.lock = lock;
    }

    @Scheduled(cron = "${app.audit.partition-cron:0 15 2 * * *}", zone = "UTC")
    public void scheduled() {
        try {
            run();
        } catch (RuntimeException e) {
            log.error("Audit partition job failed", e);
        }
    }

    public int run() {
        Integer created = tx.execute(status -> lock.tryLock("audit-partitions", GLOBAL)
                ? jdbc.queryForObject("SELECT ensure_audit_event_partitions(" + MONTHS_AHEAD + ")", Integer.class)
                : Integer.valueOf(0));
        log.info("Audit partition job created {} partition(s)", created);
        return created == null ? 0 : created;
    }
}
