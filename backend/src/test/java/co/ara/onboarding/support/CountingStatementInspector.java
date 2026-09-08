package co.ara.onboarding.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records every SQL statement Hibernate sends to the database, for tests that
 * need to prove a code path issues exactly N queries (e.g. "one query
 * regardless of how many milestones a roadmap has", TaskDirectoryTest).
 *
 * Wired per test via the {@code spring.jpa.properties.hibernate.session_factory
 * .statement_inspector} property, set to this class's plain name --
 * Hibernate instantiates the configured StatementInspector by reflection
 * (a bare no-arg constructor), with no Spring involvement at all, so there is
 * no way to inject per-test state into an instance of this class. The
 * recorded statements therefore live on a static field instead, and
 * {@link StatementCounter} is the test-facing reset/read API over that same
 * static list -- see its own javadoc for why a static list is safe here
 * (this suite's tests do not run the affected class in parallel).
 */
public class CountingStatementInspector implements StatementInspector {

    static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql);
        return sql;
    }
}
