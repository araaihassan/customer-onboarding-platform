package co.ara.onboarding;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import static org.assertj.core.api.Assertions.assertThat;

class ApplicationContextTest extends PostgresTestBase {

    @Autowired DataSource dataSource;

    @Test
    void connectsToRealPostgres() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
    }

    @Test
    void flywayAppliesV1Baseline() throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "select success from flyway_schema_history where version = '1'")) {
            assertThat(rs.next())
                    .as("expected a flyway_schema_history row for version 1 (V1__baseline.sql)")
                    .isTrue();
            assertThat(rs.getBoolean("success")).isTrue();
        }
    }
}
