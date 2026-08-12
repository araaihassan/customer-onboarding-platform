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
}
