package co.ara.onboarding.audit;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * audit_event is range-partitioned by month, and a month with no partition of
 * its own silently lands in audit_event_default. That is not data loss, but it
 * is a trap: Postgres refuses to create a month's partition while DEFAULT still
 * holds rows in its range, so the longer it goes unnoticed the bigger the move
 * that V27__audit_partitions_forward.sql had to do once already.
 *
 * Deliberately measured against the real UTC date, not the suite's
 * MutableClock: the question is whether the schema covers the months
 * production is about to write into, and production stamps occurred_at from
 * Clock.systemUTC(). Until sub-project 6's job rolls partitions forward, this
 * test goes red three months before V27's headroom runs out -- which is the
 * point.
 */
class AuditPartitionCoverageTest extends PostgresTestBase {

    private static final int MONTHS_AHEAD = 3;

    @Test
    void thePartitionsCoverTheCurrentMonthAndTheNextThree() {
        YearMonth now = YearMonth.from(LocalDate.now(ZoneOffset.UTC));
        List<String> expected = Stream.iterate(now, m -> m.plusMonths(1))
                .limit(MONTHS_AHEAD + 1)
                .map(m -> "audit_event_%d_%02d".formatted(m.getYear(), m.getMonthValue()))
                .toList();

        List<String> partitions = ownerJdbc().queryForList("""
                SELECT c.relname
                  FROM pg_inherits i
                  JOIN pg_class c ON c.oid = i.inhrelid
                 WHERE i.inhparent = 'audit_event'::regclass
                """, String.class);

        assertThat(partitions).containsAll(expected);
    }

    @Test
    void theDefaultPartitionHoldsNothingFromTheCurrentMonth() {
        LocalDate monthStart = YearMonth.from(LocalDate.now(ZoneOffset.UTC)).atDay(1);

        Integer stray = ownerJdbc().queryForObject(
                "SELECT count(*) FROM audit_event_default WHERE occurred_at >= ?::date AND occurred_at < ?::date",
                Integer.class, monthStart.toString(), monthStart.plusMonths(1).toString());

        assertThat(stray).isZero();
    }
}
