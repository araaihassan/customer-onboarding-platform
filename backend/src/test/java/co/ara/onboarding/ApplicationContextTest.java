package co.ara.onboarding;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import java.sql.Connection;
import static org.assertj.core.api.Assertions.assertThat;

class ApplicationContextTest extends PostgresTestBase {

    @Autowired DataSource dataSource;

    @Test
    void connectsToRealPostgres() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
    }
}
