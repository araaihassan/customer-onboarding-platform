package co.ara.onboarding.architecture;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Generates the OpenAPI document and writes it to build/openapi.json, which Task 23
 * feeds to openapi-typescript.
 *
 * A test rather than the plan's Gradle task that prints a curl command, for two
 * reasons. The contract becomes a real build artifact, produced by `./gradlew test`
 * with no manually started server — so CI can diff it and the frontend can be
 * generated from a checkout. And springdoc genuinely can fail to describe a type,
 * which would otherwise surface as a broken frontend build rather than a failing
 * backend test.
 *
 * Writing a file from a test is a deliberate impurity; the alternative is a
 * contract nobody can regenerate without running the application by hand.
 */
@AutoConfigureMockMvc
class OpenApiDocumentTest extends PostgresTestBase {

    private static final Path OUTPUT = Path.of("build", "openapi.json");

    @Autowired MockMvc mvc;

    @Test
    void generatesTheDocumentAndWritesItForTheFrontend() throws Exception {
        String document = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The endpoints the frontend is built against. If springdoc silently stops
        // describing one, the generated client loses it and the failure appears in
        // TypeScript rather than here.
        assertThat(document)
                .contains("/api/t/{tenantSlug}/auth/login")
                .contains("/api/t/{tenantSlug}/auth/refresh")
                .contains("/api/t/{tenantSlug}/me");

        // The typed record, not an untyped object. A Map<String,Object> response
        // would serialise the same and document as nothing useful.
        assertThat(document).contains("\"permissions\"").contains("\"userType\"");

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, document);

        assertThat(OUTPUT).exists();
        assertThat(Files.size(OUTPUT)).isPositive();
    }
}
