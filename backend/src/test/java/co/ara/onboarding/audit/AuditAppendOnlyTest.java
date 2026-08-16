package co.ara.onboarding.audit;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditAppendOnlyTest extends PostgresTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void applicationRoleCannotUpdateOrDeleteAuditEvents() {
        // hasStackTraceContaining, not hasMessageContaining: JdbcTemplate wraps the
        // driver error in a BadSqlGrammarException whose own message is generic,
        // so the Postgres text only appears on the cause.
        assertThatThrownBy(() -> jdbc.execute("UPDATE audit_event SET summary = 'tampered'"))
                .hasStackTraceContaining("permission denied for table audit_event");

        assertThatThrownBy(() -> jdbc.execute("DELETE FROM audit_event"))
                .hasStackTraceContaining("permission denied for table audit_event");
    }

    // Partitions are independent relations that inherit V2's default-privilege
    // GRANT SELECT, INSERT, UPDATE at CREATE TABLE time; naming one directly
    // bypasses both the append-only grant AND RLS applied only to the parent
    // (V5's enable_tenant_rls('audit_event') does not reach partitions accessed
    // by their own name). V5_1 closes this by revoking the schema-wide default
    // grant, stripping what the partitions already inherited, and enabling RLS
    // on each partition as defence in depth.
    @Test
    void applicationRoleCannotAccessPartitionsDirectly() {
        assertThatThrownBy(() -> jdbc.execute("SELECT * FROM audit_event_2026_08"))
                .hasStackTraceContaining("permission denied for table audit_event_2026_08");

        assertThatThrownBy(() -> jdbc.execute("UPDATE audit_event_2026_08 SET summary = 'tampered'"))
                .hasStackTraceContaining("permission denied for table audit_event_2026_08");
    }
}
