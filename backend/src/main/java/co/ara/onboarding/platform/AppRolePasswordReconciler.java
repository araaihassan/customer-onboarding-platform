package co.ara.onboarding.platform;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Keeps the onboarding_app login role's real PostgreSQL password in sync with
 * DB_APP_PASSWORD, on every startup.
 *
 * V2__app_role_and_tenant.sql creates the role once, with the committed literal
 * password 'onboarding_app', guarded IF NOT EXISTS -- and migrations are
 * forward-only, so that statement can never change. A guard that simply refused
 * a blank or literal DB_APP_PASSWORD (DatabaseCredentialsGuard, in this same
 * package) would still leave the role's actual password at that literal forever,
 * because nothing else in the system ever runs ALTER ROLE. The naive fix -- ask
 * every environment to remember a manual ALTER ROLE step -- is exactly the
 * "rotated operationally" gap CLAUDE.md used to describe; this callback
 * automates it instead.
 *
 * Registered as a Flyway {@link Callback} bean: Spring Boot's FlywayAutoConfiguration
 * collects every Callback bean via ObjectProvider and hands them to Flyway, no
 * further wiring needed. It fires on {@link Event#AFTER_MIGRATE}, which Flyway
 * raises on every migrate() invocation -- including one where zero new versioned
 * migrations apply -- so the role stays reconciled indefinitely across restarts
 * and rotations alike, not just on the run that first creates it.
 *
 * The ALTER ROLE runs on context.getConnection(), which for a migrate-or-undo
 * event Flyway's own DefaultCallbackExecutor.onMigrateOrUndoEvent resolves to
 * Database.getEventConnection() -- a connection opened fresh, via the same
 * connection factory Flyway itself was configured with (bound to the owner
 * credentials -- spring.flyway.user/password, i.e. DB_OWNER_USER/DB_OWNER_PASSWORD
 * -- never the app datasource, which may not even be able to authenticate yet),
 * and disposed immediately after the callback returns
 * (Database.disposeEventConnection()). This is deliberately NOT the connection
 * Flyway used to run the migrations themselves (getMigrationConnection()):
 * Flyway's own javadoc on getEventConnection() states why a separate one exists --
 * "if using the migration connection instead, it may trigger an unwanted commit
 * which breaks any ongoing migration transaction." Using the dedicated event
 * connection means this callback's ALTER ROLE can never interfere with Flyway's
 * own migration transaction, by Flyway's design rather than by luck; it still
 * needs no owner-credential wiring of its own, since the event connection is
 * already bound to them.
 *
 * Ordering: Boot's own FlywayMigrationInitializer completes Flyway's migrate()
 * call (callbacks included) during context refresh before JPA schema validation
 * or any business-logic bean does real work -- the same guarantee that already
 * makes hibernate.ddl-auto: validate reliable against this migration set. This
 * callback is itself one of Flyway's own bean dependencies (Spring must construct
 * it before the Flyway bean it feeds), so the reconciliation is guaranteed to run
 * before anything downstream of Flyway opens its first real connection through
 * spring.datasource.*. Proven empirically, not just argued, by
 * AppRolePasswordReconcilerTest.
 */
@Component
public class AppRolePasswordReconciler implements Callback {

    private final String password;

    public AppRolePasswordReconciler(@Value("${DB_APP_PASSWORD:}") String password) {
        this.password = password;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        // ALTER ROLE ... PASSWORD is ordinary transactional DDL in PostgreSQL.
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        // DatabaseCredentialsGuard normally refuses startup on a blank or
        // denylisted DB_APP_PASSWORD before this callback ever runs -- but that
        // is incidental bean-construction ordering, not an enforced dependency
        // between the two beans (no @DependsOn, nothing wiring one to the
        // other). If that ordering ever inverted, this callback would otherwise
        // be the only thing standing between a misconfigured startup and
        // silently resetting the live onboarding_app role's password back to
        // the published literal, before the guard got a chance to fail the
        // startup -- reopening the exact hole this task exists to close. So
        // this callback re-checks for itself, rather than trusting the guard
        // ran first.
        DatabaseCredentialsGuard.requireUsablePassword(password);

        // Single-quotes doubled per standard SQL string-literal escaping. ALTER
        // ROLE's PASSWORD clause takes a string literal, not a bind parameter
        // position PostgreSQL's extended query protocol will accept here, so this
        // is built by hand rather than through a PreparedStatement placeholder.
        String escaped = password.replace("'", "''");
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("ALTER ROLE onboarding_app PASSWORD '" + escaped + "'");
        } catch (SQLException e) {
            // Never include the password (or its escaped form) in the exception
            // message, for the same reason DatabaseCredentialsGuard's own failures
            // never echo the rejected value.
            throw new IllegalStateException(
                    "Failed to reconcile the onboarding_app role's password to DB_APP_PASSWORD.", e);
        }
    }

    @Override
    public String getCallbackName() {
        return "appRolePasswordReconciler";
    }
}
