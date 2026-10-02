package co.ara.onboarding.agreement;

import co.ara.onboarding.auth.TokenService;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
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
import co.ara.onboarding.identity.AppUserRepository;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task 21: the signature endpoint's file part against the container's multipart ceiling. Like
 * {@code document.MultipartUploadSizeTest}, only a real server exercises the container's own
 * multipart resolver (MockMvc never does), so this runs on a random port with the ceiling
 * lowered to 1 KiB. The container refuses the request before any controller or service runs, so
 * no agreement needs to exist -- what is proven is that this route is covered by the same 413
 * mapping, not a raw 500.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.storage.max-upload-bytes=1024")
class AgreementMultipartSizeTest extends PostgresTestBase {

    @Autowired TestRestTemplate rest;
    @Autowired TenantFixture fixture;
    @Autowired TokenService tokens;
    @Autowired AppUserRepository appUsers;

    @Test
    void anOversizeCountersignedFileAnswers413() {
        String slug = "agr-size-" + Uuid7.generate();
        UUID tenant = fixture.createTenant(slug);
        UUID adminId = fixture.createAdministrator(tenant, "admin+" + Uuid7.generate() + "@example.com");
        AppUser admin = fixture.runAsReturning(tenant, () -> appUsers.findById(adminId).orElseThrow());

        byte[] tooLarge = new byte[4096];
        Arrays.fill(tooLarge, (byte) 'A');
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        HttpEntity<ByteArrayResource> filePart = new HttpEntity<>(new ByteArrayResource(tooLarge) {
            @Override
            public String getFilename() {
                return "signed.bin";
            }
        }, fileHeaders);
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> signaturePart = new HttpEntity<>("{\"signatoryId\":\"" + Uuid7.generate()
                + "\",\"signedOn\":\"2026-01-01\",\"method\":\"Wet ink\",\"lockVersion\":0}", jsonHeaders);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("signature", signaturePart);
        body.add("file", filePart);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokens.issueAccessToken(admin));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = rest.exchange(
                "/api/t/" + slug + "/agreements/" + Uuid7.generate() + "/signatures", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertEquals(413, response.getStatusCode().value(), response.getBody());
    }
}
