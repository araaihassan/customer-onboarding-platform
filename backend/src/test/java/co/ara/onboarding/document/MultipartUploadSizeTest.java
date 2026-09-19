package co.ara.onboarding.document;

import co.ara.onboarding.auth.TokenService;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 22 review Finding 1, verified against a REAL running server -- MockMvc
 * cannot exercise this at all, confirmed by reading {@code DispatcherServlet
 * #checkMultipart}: it treats a request that already implements {@code
 * MultipartHttpServletRequest} (which every {@code MockMvcRequestBuilders
 * .multipart(...)} request already does -- {@code MockMultipartHttpServletRequest}
 * implements that interface directly) as "already resolved" and never invokes
 * the real {@code StandardServletMultipartResolver} -- the exact container-level
 * parsing this ceiling depends on. {@code DocumentControllerTest}'s own
 * multipart tests are therefore structurally blind to this bug, the same way
 * {@code security.ErrorDispatchAuthenticationTest}'s own javadoc explains
 * MockMvc is blind to the container's {@code /error} forward -- this class
 * needed that same {@code webEnvironment = RANDOM_PORT} + {@code
 * TestRestTemplate} shape, the only other place in this suite that does.
 *
 * <p>{@code app.storage.max-upload-bytes} is overridden down to 1 KiB for this
 * class only (a fresh Spring context, the same trade-off {@code
 * ErrorDispatchAuthenticationTest}'s own {@code RANDOM_PORT} context already
 * makes), so the test uploads a few KiB over the ceiling rather than needing a
 * real 25 MiB+ payload to prove the identical thing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.storage.max-upload-bytes=1024")
class MultipartUploadSizeTest extends PostgresTestBase {

    @Autowired TestRestTemplate rest;
    @Autowired TenantFixture fixture;
    @Autowired TokenService tokens;
    @Autowired RoleService roles;
    @Autowired JourneyFixtures journey;

    private String tenantSlug;
    private UUID tenant;
    private AppUser actor;
    private UUID caseId;

    @BeforeEach
    void seedATenantWithAFullyGrantedActorAndACase() {
        tenantSlug = "multipart-size-" + Uuid7.generate();
        tenant = fixture.createTenant(tenantSlug);
        actor = fixture.createUserWithPassword(tenant,
                "actor+" + Uuid7.generate() + "@multipart-size.example", "long-enough-password");

        fixture.runAs(tenant, () -> {
            UUID role = roles.createRole("Upload Size Actor " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
            roles.assignRole(actor.getId(), role);

            Case c = journey.newCase(tenant);
            caseId = c.getId();
        });
    }

    /**
     * Before this task's fix, this request 500'd -- the servlet container's own
     * unconfigured multipart default (1 MiB) rejected it as an unmapped {@code
     * MaxUploadSizeExceededException} before {@code DocumentController#upload}
     * was ever invoked. Now it answers 413 with a real {@link
     * org.springframework.http.ProblemDetail} body naming the configured
     * ceiling, indistinguishable in shape from {@link UploadTooLargeException}'s
     * own 413.
     */
    @Test
    void anUploadOverTheContainersConfiguredCeilingAnswers413WithAProblemDetailNotARaw500() {
        byte[] tooLarge = new byte[4096]; // comfortably over the 1024-byte ceiling configured above
        Arrays.fill(tooLarge, (byte) 'A');

        HttpHeaders filePartHeaders = new HttpHeaders();
        filePartHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        HttpEntity<ByteArrayResource> filePart = new HttpEntity<>(new ByteArrayResource(tooLarge) {
            @Override
            public String getFilename() {
                return "too-large.bin";
            }
        }, filePartHeaders);

        HttpHeaders metadataPartHeaders = new HttpHeaders();
        metadataPartHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> metadataPart = new HttpEntity<>(
                "{\"name\":\"Too Large.bin\",\"category\":\"CONTRACT\",\"visibilityTier\":\"COMPANY_SHARED\"}",
                metadataPartHeaders);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);
        body.add("metadata", metadataPart);

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setBearerAuth(tokens.issueAccessToken(actor));
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = rest.exchange(
                "/api/t/" + tenantSlug + "/cases/" + caseId + "/documents", HttpMethod.POST,
                new HttpEntity<>(body, requestHeaders), String.class);

        assertEquals(413, response.getStatusCode().value(), response.getBody());
        assertTrue(response.getBody() != null && response.getBody().contains("app.storage.max-upload-bytes"),
                "expected a ProblemDetail body naming app.storage.max-upload-bytes, got: " + response.getBody());
    }
}
