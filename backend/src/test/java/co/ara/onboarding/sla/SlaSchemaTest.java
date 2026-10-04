package co.ara.onboarding.sla;

import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlaSchemaTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;

    @Test
    void theApplicationRoleCannotDeleteFromAnySlaTable() {
        for (String table : new String[]{"sla_clock", "sla_pause", "escalation", "notification"}) {
            withAppConnection(jdbc -> assertThatThrownBy(() -> jdbc.execute("DELETE FROM " + table))
                    .hasStackTraceContaining("permission denied for table " + table));
        }
    }

    @Test
    void escalationIsUniquePerSubjectAndDueDate() {
        Integer constraints = ownerJdbc().queryForObject("""
                SELECT count(*) FROM pg_constraint
                 WHERE conrelid = 'escalation'::regclass AND contype = 'u'""", Integer.class);
        assertThat(constraints).isPositive();
    }

    @Test
    void aCaseHasAtMostOneOpenClock() {
        Integer index = ownerJdbc().queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE indexname = 'sla_clock_one_open_per_case'""", Integer.class);
        assertThat(index).isOne();
    }

    @Test
    void documentRequestCarriesReminderCounters() {
        Integer cols = ownerJdbc().queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'document_request' AND column_name IN ('reminders_sent', 'last_reminded_at')""",
                Integer.class);
        assertThat(cols).isEqualTo(2);
    }

    private UUID[] seedCaseAndStage() {
        UUID tenant = fixture.createTenant("sla-schema-" + Uuid7.generate());
        AtomicReference<UUID[]> ids = new AtomicReference<>();
        fixture.runAs(tenant, () -> {
            var c = journey.newCase(tenant);
            ids.set(new UUID[]{tenant, c.getId(), journey.newStage(tenant, c.getVersionId())});
        });
        return ids.get();
    }

    private static final String INSERT_CLOCK = """
            INSERT INTO sla_clock (id, tenant_id, case_id, stage_id, target_days, pause_eligible,
                                   started_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, 3, true, now(), now(), now())""";

    @Test
    void aSecondOpenClockForTheSameCaseIsRefused() {
        UUID[] s = seedCaseAndStage();
        var jdbc = ownerJdbc();
        jdbc.update(INSERT_CLOCK, Uuid7.generate(), s[0], s[1], s[2]);
        assertThatThrownBy(() -> jdbc.update(INSERT_CLOCK, Uuid7.generate(), s[0], s[1], s[2]))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aSecondOpenPauseForTheSameReasonIsRefused() {
        UUID[] s = seedCaseAndStage();
        var jdbc = ownerJdbc();
        UUID clock = Uuid7.generate();
        jdbc.update(INSERT_CLOCK, clock, s[0], s[1], s[2]);
        String pause = """
                INSERT INTO sla_pause (id, tenant_id, clock_id, reason, started_at, created_at, updated_at)
                VALUES (?, ?, ?, 'CASE_HOLD', now(), now(), now())""";
        jdbc.update(pause, Uuid7.generate(), s[0], clock);
        assertThatThrownBy(() -> jdbc.update(pause, Uuid7.generate(), s[0], clock))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
