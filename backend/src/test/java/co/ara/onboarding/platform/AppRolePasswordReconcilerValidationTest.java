package co.ara.onboarding.platform;

import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.callback.Statement;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.output.OperationResult;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AppRolePasswordReconciler.handle() must refuse a blank or denylisted
 * DB_APP_PASSWORD itself, rather than relying on DatabaseCredentialsGuard
 * having already failed startup first.
 *
 * That ordering -- the guard's @PostConstruct running before Spring gets around
 * to constructing this callback -- is incidental bean-construction ordering,
 * not an enforced dependency between the two beans: no @DependsOn, nothing
 * wiring one to the other. If it ever inverted, this callback would be the
 * only thing left standing between a misconfigured startup and silently
 * resetting the live onboarding_app role's password back to the published
 * literal, before the guard got a chance to fail the startup at all --
 * reopening the exact hole this task exists to close.
 *
 * Constructed directly with no Spring context and no database, on purpose:
 * the claim under test is that handle() refuses BEFORE attempting any write,
 * so the proof has to be that it throws even when given a Context whose
 * getConnection() would blow up the test if it were ever actually called --
 * an integration test asserting "the row wasn't changed" could never
 * distinguish "never attempted" from "attempted and happened to fail",
 * whereas this can.
 */
class AppRolePasswordReconcilerValidationTest {

    /** Fails the test immediately if handle() ever tries to open a connection. */
    private static final Context CONNECTION_MUST_NOT_BE_OPENED = new Context() {
        @Override
        public Configuration getConfiguration() {
            throw new AssertionError("must not be called before validation refuses the password");
        }

        @Override
        public Connection getConnection() {
            throw new AssertionError(
                    "handle() must refuse an unusable password before ever asking for a "
                            + "connection to write through -- getConnection() should never be "
                            + "reached for a blank or denylisted DB_APP_PASSWORD");
        }

        @Override
        public MigrationInfo getMigrationInfo() {
            throw new AssertionError("must not be called before validation refuses the password");
        }

        @Override
        public Statement getStatement() {
            throw new AssertionError("must not be called before validation refuses the password");
        }

        @Override
        public OperationResult getOperationResult() {
            throw new AssertionError("must not be called before validation refuses the password");
        }
    };

    @Test
    void refusesABlankPasswordBeforeAttemptingAnyDatabaseWrite() {
        AppRolePasswordReconciler reconciler = new AppRolePasswordReconciler("");

        assertThatThrownBy(() ->
                reconciler.handle(Event.AFTER_MIGRATE, CONNECTION_MUST_NOT_BE_OPENED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_APP_PASSWORD");
    }

    @Test
    void refusesThePublishedLiteralBeforeAttemptingAnyDatabaseWrite() {
        AppRolePasswordReconciler reconciler = new AppRolePasswordReconciler("onboarding_app");

        assertThatThrownBy(() ->
                reconciler.handle(Event.AFTER_MIGRATE, CONNECTION_MUST_NOT_BE_OPENED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_APP_PASSWORD");
    }
}
