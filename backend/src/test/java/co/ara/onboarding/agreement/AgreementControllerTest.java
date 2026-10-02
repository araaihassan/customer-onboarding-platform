package co.ara.onboarding.agreement;

import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.security.SecurityTestBase;
import co.ara.onboarding.workflow.AgreementRecordMode;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 21: every spec section 8 endpoint reaches its already-gated service method through real
 * HTTP. Authorization correctness is proven at the service layer; this proves wiring, status
 * codes, the 404/409/400 mappings and the multipart signature shape.
 */
class AgreementControllerTest extends SecurityTestBase {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    @Autowired AgreementTestSupport support;
    @Autowired AppUserRepository appUsers;
    @Autowired CaseRepository cases;

    private record World(String slug, UUID tenant, AppUser editor, AppUser reviewer, UUID caseId) {}

    private AppUser user(UUID tenant, UUID id) {
        return fixture.runAsReturning(tenant, () -> appUsers.findById(id).orElseThrow());
    }

    private World world(AgreementRecordMode mode) {
        String slug = "agr-ctl-" + Uuid7.generate();
        UUID tenant = fixture.createTenant(slug);
        UUID editor = fixture.createAdministrator(tenant, "editor+" + Uuid7.generate() + "@example.com");
        UUID reviewer = fixture.createAdministrator(tenant, "reviewer+" + Uuid7.generate() + "@example.com");
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> caseId[0] = support.openCaseWithSignatureRequirement(tenant, mode));
        return new World(slug, tenant, user(tenant, editor), user(tenant, reviewer), caseId[0]);
    }

    private String base(World w) {
        return "/api/t/" + w.slug();
    }

    private AgreementTestSupport.Driven drive(World w, AgreementStatus target, int signers) {
        return support.drive(w.tenant(), w.caseId(), target, signers, w.editor().getId(), w.reviewer().getId());
    }

    @Test
    void readEndpointsReachTheService() throws Exception {
        World w = world(AgreementRecordMode.STRUCTURED_ONLY);
        var d = drive(w, AgreementStatus.DRAFT, 1);
        String b = base(w);

        mvc.perform(as(get(b + "/agreements"), w.editor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(d.agreementId().toString()));
        mvc.perform(as(get(b + "/agreements").param("status", "DRAFT").param("page", "0").param("size", "5"), w.editor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").exists());
        mvc.perform(as(get(b + "/agreements/summary"), w.editor())).andExpect(status().isOk());
        mvc.perform(as(get(b + "/cases/" + d.caseId() + "/agreements"), w.editor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(d.agreementId().toString()));
        mvc.perform(as(get(b + "/agreements/" + d.agreementId()), w.editor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.agreement.id").value(d.agreementId().toString()));
    }

    @Test
    void anUnknownOrCrossTenantIdIsA404() throws Exception {
        World w = world(AgreementRecordMode.STRUCTURED_ONLY);
        World other = world(AgreementRecordMode.STRUCTURED_ONLY);
        var foreign = drive(other, AgreementStatus.DRAFT, 1);

        mvc.perform(as(get(base(w) + "/agreements/" + Uuid7.generate()), w.editor())).andExpect(status().isNotFound());
        mvc.perform(as(get(base(w) + "/agreements/" + foreign.agreementId()), w.editor())).andExpect(status().isNotFound());
        mvc.perform(as(post(base(w) + "/agreements/" + foreign.agreementId() + "/submit")
                .contentType(MediaType.APPLICATION_JSON).content("{\"lockVersion\":0}"), w.editor()))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchAndSignatoriesAndValidationAndStaleLock() throws Exception {
        World w = world(AgreementRecordMode.STRUCTURED_ONLY);
        var d = drive(w, AgreementStatus.DRAFT, 1);
        String url = base(w) + "/agreements/" + d.agreementId();

        mvc.perform(as(patch(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"lockVersion\":" + d.lockVersion() + "}"), w.editor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.agreement.name").value("Renamed"));

        // The same lockVersion again is now stale.
        mvc.perform(as(patch(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Again\",\"lockVersion\":" + d.lockVersion() + "}"), w.editor()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("This agreement changed since you loaded it — reload and try again."));

        long lock = JsonPath.<Integer>read(mvc.perform(as(get(url), w.editor())).andReturn().getResponse()
                .getContentAsString(), "$.agreement.lockVersion").longValue();

        mvc.perform(as(patch(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"noticePeriodDays\":-1,\"lockVersion\":" + lock + "}"), w.editor()))
                .andExpect(status().isBadRequest());

        mvc.perform(as(put(url + "/signatories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signatories\":[],\"lockVersion\":" + lock + "}"), w.editor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.signatories.length()").value(0));
        mvc.perform(as(put(url + "/signatories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockVersion\":" + lock + "}"), w.editor()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadDraftFileTakesAMultipartFileAndALockVersionParameter() throws Exception {
        World w = world(AgreementRecordMode.FILE_BACKED);
        String agreementId = JsonPath.read(mvc.perform(as(get(base(w) + "/cases/" + w.caseId() + "/agreements"),
                w.editor())).andReturn().getResponse().getContentAsString(), "$[0].id");
        String detail = mvc.perform(as(get(base(w) + "/agreements/" + agreementId), w.editor())).andReturn()
                .getResponse().getContentAsString();
        long lock = JsonPath.<Integer>read(detail, "$.agreement.lockVersion").longValue();

        mvc.perform(as(multipart(base(w) + "/agreements/" + agreementId + "/file").file(
                        new MockMultipartFile("file", "draft.pdf", "application/pdf", PDF_BYTES))
                .param("lockVersion", String.valueOf(lock)), w.editor()))
                .andExpect(status().isOk());
    }

    @Test
    void submitReviewSendRecordRoundTripThroughHttp() throws Exception {
        World w = world(AgreementRecordMode.STRUCTURED_ONLY);
        var d = drive(w, AgreementStatus.DRAFT, 1);
        String url = base(w) + "/agreements/" + d.agreementId();

        String submitted = mvc.perform(as(post(url + "/submit").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockVersion\":" + d.lockVersion() + "}"), w.editor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.agreement.status").value("UNDER_REVIEW"))
                .andReturn().getResponse().getContentAsString();
        long lock = JsonPath.<Integer>read(submitted, "$.agreement.lockVersion").longValue();
        int n = JsonPath.<Integer>read(submitted, "$.versions[0].versionNumber");

        // Four eyes: the submitter is refused with the specific message.
        mvc.perform(as(post(url + "/versions/" + n + "/review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"lockVersion\":" + lock + "}"), w.editor()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("You submitted or last edited this version, so someone else must review it."));

        // A missing decision is a 400.
        mvc.perform(as(post(url + "/versions/" + n + "/review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockVersion\":" + lock + "}"), w.reviewer()))
                .andExpect(status().isBadRequest());

        String approved = mvc.perform(as(post(url + "/versions/" + n + "/review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"lockVersion\":" + lock + "}"), w.reviewer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.agreement.status").value("APPROVED"))
                .andReturn().getResponse().getContentAsString();
        lock = JsonPath.<Integer>read(approved, "$.agreement.lockVersion").longValue();

        String sent = mvc.perform(as(post(url + "/send").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockVersion\":" + lock + "}"), w.editor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.agreement.status").value("SENT"))
                .andReturn().getResponse().getContentAsString();
        lock = JsonPath.<Integer>read(sent, "$.agreement.lockVersion").longValue();
        String signatoryId = JsonPath.read(sent, "$.signatories[0].id");

        String json = "{\"signatoryId\":\"" + signatoryId + "\",\"signedOn\":\""
                + LocalDate.now(ZoneOffset.UTC).minusDays(1) + "\",\"method\":\"Wet ink\",\"lockVersion\":" + lock + "}";
        mvc.perform(as(multipart(url + "/signatures").file(
                        new MockMultipartFile("signature", "", "application/json", json.getBytes(StandardCharsets.UTF_8))),
                w.editor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.agreement.status").value("SIGNED"));
    }

    @Test
    void signatureFilePartIsForwardedAndCancelIsWired() throws Exception {
        World w = world(AgreementRecordMode.STRUCTURED_ONLY);
        var d = drive(w, AgreementStatus.SENT, 1);
        String url = base(w) + "/agreements/" + d.agreementId();

        // STRUCTURED_ONLY takes no countersigned file: the service's own 400 proves the file part is forwarded.
        String json = "{\"signatoryId\":\"" + d.signatoryIds().get(0) + "\",\"signedOn\":\""
                + LocalDate.now(ZoneOffset.UTC).minusDays(1) + "\",\"method\":\"Wet ink\",\"lockVersion\":"
                + d.lockVersion() + "}";
        mvc.perform(as(multipart(url + "/signatures")
                        .file(new MockMultipartFile("signature", "", "application/json", json.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("file", "signed.pdf", "application/pdf", PDF_BYTES)), w.editor()))
                .andExpect(status().isBadRequest());

        mvc.perform(as(post(url + "/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\",\"lockVersion\":" + d.lockVersion() + "}"), w.editor()))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post(url + "/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Terms changed\",\"lockVersion\":" + d.lockVersion() + "}"), w.editor()))
                .andExpect(status().isOk())
                // The response is the successor draft; the cancelled original is read back by id.
                .andExpect(jsonPath("$.agreement.status").value("DRAFT"));
        mvc.perform(as(get(url), w.editor()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.agreement.status").value("CANCELLED"));
    }

    @Test
    void portalEndpointsServeAPortalContactAndHideFromOperators() throws Exception {
        World w = world(AgreementRecordMode.STRUCTURED_ONLY);
        var d = drive(w, AgreementStatus.SENT, 1);
        UUID customer = fixture.runAsReturning(w.tenant(), () -> cases.findById(w.caseId()).orElseThrow().getCustomerId());
        UUID portalId = fixture.createPortalUserForContact(w.tenant(), customer,
                "portal+" + Uuid7.generate() + "@example.com");
        AppUser portal = user(w.tenant(), portalId);

        mvc.perform(as(get(base(w) + "/portal/agreements"), portal))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(d.agreementId().toString()));
        mvc.perform(as(get(base(w) + "/portal/agreements/" + d.agreementId()), portal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(d.agreementId().toString()));
        mvc.perform(as(get(base(w) + "/portal/agreements/" + Uuid7.generate()), portal))
                .andExpect(status().isNotFound());
        mvc.perform(as(get(base(w) + "/portal/agreements"), w.editor())).andExpect(status().isNotFound());
    }
}
