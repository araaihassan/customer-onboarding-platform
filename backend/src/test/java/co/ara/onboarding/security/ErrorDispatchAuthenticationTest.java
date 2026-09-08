package co.ara.onboarding.security;

import co.ara.onboarding.auth.TokenService;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A bug only a REAL running server can expose, found live by Playwright's very
 * first run against this branch, never by {@code ./gradlew cleanTest test}: every
 * *ApiTest in this suite goes through MockMvc, whose {@code TestDispatcherServlet}
 * never performs the container-level forward to {@code /error} that Boot's
 * default exception handling triggers via {@code response.sendError()} — so a
 * MockMvc test is structurally blind to what happens on that second pass.
 *
 * The mechanism: {@link co.ara.onboarding.auth.JwtAuthenticationFilter} is a plain
 * {@code OncePerRequestFilter}, which — by Spring's own default —
 * {@code shouldNotFilterErrorDispatch()}, i.e. skips itself entirely on the
 * container's internal ERROR-dispatch forward. This app has no
 * {@code @ExceptionHandler} for {@code MethodArgumentNotValidException} (or any
 * other framework-level validation/parsing exception), so
 * {@code DefaultHandlerExceptionResolver} falls back to
 * {@code response.sendError(400, ...)}, which Tomcat turns into an internal
 * forward to {@code /error} — a SECOND pass through Spring Security's filter
 * chain. On that second pass the SecurityContext is empty (the JWT filter never
 * ran), {@code .anyRequest().authenticated()} in {@code SecurityConfig} rejects
 * it, and the configured entry point overwrites the already-set 400 with 401.
 *
 * The result: a fully authenticated, fully authorized request whose BODY merely
 * fails bean validation answers 401, not 400 — indistinguishable, to any caller,
 * from an expired or invalid session. That is exactly the signal
 * {@code SecurityConfig}'s own comment says the frontend's silent-refresh logic
 * acts on ("Task 24's API client refreshes on 401 only"), so this is not cosmetic:
 * a plain validation mistake looks like a dead session to the one piece of code
 * that treats 401 as meaningful.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorDispatchAuthenticationTest extends PostgresTestBase {

    @Autowired TestRestTemplate rest;
    @Autowired TenantFixture fixture;
    @Autowired TokenService tokens;

    @Test
    void aValidationFailureOnAnAuthenticatedRequestAnswers400NotUnauthorized() {
        UUID tenant = fixture.createTenant("err-dispatch");
        AppUser admin = fixture.createAdminUser(tenant, "errdispatch@example.com");
        String token = tokens.issueAccessToken(admin);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> created = rest.exchange(
                "/api/t/err-dispatch/workflows", HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"Standard\",\"description\":\"\"}", headers), String.class);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        String templateId = JsonPath.read(created.getBody(), "$.id");

        ResponseEntity<String> draft = rest.exchange(
                "/api/t/err-dispatch/workflows/" + templateId + "/versions", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
        assertEquals(201, draft.getStatusCode().value(), draft.getBody());
        String versionId = JsonPath.read(draft.getBody(), "$.versionId");

        // Missing estimatedDurationDays: Jackson binds the missing primitive int to
        // 0, and @Positive rejects it -- a plain, ordinary validation failure with
        // nothing to do with authentication.
        String badBody = "{\"stages\":[{\"key\":\"s1\",\"name\":\"S1\","
                + "\"milestones\":[{\"key\":\"m1\",\"name\":\"M1\"}]}]}";
        ResponseEntity<String> response = rest.exchange(
                "/api/t/err-dispatch/workflows/" + templateId + "/versions/" + versionId,
                HttpMethod.PUT, new HttpEntity<>(badBody, headers), String.class);

        assertEquals(400, response.getStatusCode().value(), response.getBody());
    }
}
