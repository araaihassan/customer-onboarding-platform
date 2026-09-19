package co.ara.onboarding.document;

import co.ara.onboarding.auth.TokenService;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 26 (brief Step 1's own third named test): the SAME global {@code
 * app.storage.max-upload-bytes} multipart ceiling {@link MultipartUploadSizeTest}
 * proves for the internal upload endpoint, proven again here for {@code POST
 * /portal/cases/{caseId}/documents} -- Task 7's ruling is inherited by reusing
 * {@link DocumentService}'s shared {@code captureContent}/size-check machinery
 * (see {@link DocumentService#uploadFromPortal}), but the 413 status itself
 * comes from the SERVLET CONTAINER's own multipart resolver rejecting the
 * request before Spring MVC ever routes it to a controller -- a global
 * concern, wired once, not per-controller -- so this is exercised at the
 * portal endpoint too rather than assumed.
 *
 * <p>A SEPARATE class from {@link PortalDocumentTest}, for the identical
 * reason {@link MultipartUploadSizeTest} is separate from {@code
 * DocumentControllerTest}: {@code MockMvcRequestBuilders.multipart(...)}
 * already implements {@code MultipartHttpServletRequest}, which {@code
 * DispatcherServlet#checkMultipart} treats as "already resolved" and never
 * hands to the real {@code StandardServletMultipartResolver} -- so a MockMvc
 * test is structurally blind to this exact bug, and only a real
 * {@code webEnvironment = RANDOM_PORT} + {@code TestRestTemplate} round trip
 * (this class's own shape, identical to {@link MultipartUploadSizeTest}'s)
 * can prove it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.storage.max-upload-bytes=1024")
class PortalMultipartUploadSizeTest extends PostgresTestBase {

    @Autowired TestRestTemplate rest;
    @Autowired TenantFixture fixture;
    @Autowired TokenService tokens;
    @Autowired JourneyFixtures journey;
    @Autowired AppUserRepository appUsers;

    private String tenantSlug;
    private UUID tenant;
    private AppUser portalContact;
    private UUID caseId;

    @BeforeEach
    void seedATenantWithAPortalContactAndACase() {
        tenantSlug = "portal-multipart-size-" + Uuid7.generate();
        tenant = fixture.createTenant(tenantSlug);

        var customerId = new UUID[1];
        fixture.runAs(tenant, () -> {
            customerId[0] = fixture.createCustomer(tenant, "Portal Size Co " + Uuid7.generate(), null, null, null);
            caseId = journey.newCaseForCustomer(tenant, customerId[0]).getId();
        });

        UUID contactUserId = fixture.createPortalUserForContact(
                tenant, customerId[0], "portal-size+" + Uuid7.generate() + "@multipart-size.example");
        fixture.runAs(tenant, () -> portalContact = appUsers.findById(contactUserId).orElseThrow());
    }

    /**
     * Identical shape to {@link MultipartUploadSizeTest
     * #anUploadOverTheContainersConfiguredCeilingAnswers413WithAProblemDetailNotARaw500},
     * against the portal endpoint instead of the internal one.
     */
    @Test
    void aPortalUploadOverTheContainersConfiguredCeilingAnswers413WithAProblemDetailNotARaw500() {
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
        requestHeaders.setBearerAuth(tokens.issueAccessToken(portalContact));
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = rest.exchange(
                "/api/t/" + tenantSlug + "/portal/cases/" + caseId + "/documents", HttpMethod.POST,
                new HttpEntity<>(body, requestHeaders), String.class);

        assertEquals(413, response.getStatusCode().value(), response.getBody());
        assertTrue(response.getBody() != null && response.getBody().contains("app.storage.max-upload-bytes"),
                "expected a ProblemDetail body naming app.storage.max-upload-bytes, got: " + response.getBody());
    }
}
