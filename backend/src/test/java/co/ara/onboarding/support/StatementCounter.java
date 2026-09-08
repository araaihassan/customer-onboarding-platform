package co.ara.onboarding.support;

/**
 * Test-facing read/reset API over {@link CountingStatementInspector}'s
 * recorded SQL. {@link #reset()} clears whatever an earlier test (or earlier
 * code in the same test) left behind and returns a counter bound to whatever
 * is recorded from that point on -- call it immediately before the operation
 * under test, never before unrelated setup, or setup's own queries will be
 * counted too.
 *
 * A test using this must also register {@code CountingStatementInspector} via
 * {@code @DynamicPropertySource} on {@code
 * spring.jpa.properties.hibernate.session_factory.statement_inspector} --
 * this class only reads the static list that inspector fills in, it does not
 * wire it.
 */
public final class StatementCounter {

    public static StatementCounter reset() {
        CountingStatementInspector.STATEMENTS.clear();
        return new StatementCounter();
    }

    private StatementCounter() {}

    /** Case-insensitive: Hibernate's own generated SQL casing is not a contract. */
    public long countMatching(String substring) {
        String needle = substring.toLowerCase();
        return CountingStatementInspector.STATEMENTS.stream()
                .filter(sql -> sql.toLowerCase().contains(needle))
                .count();
    }
}
