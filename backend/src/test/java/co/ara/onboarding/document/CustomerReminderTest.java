package co.ara.onboarding.document;

import co.ara.onboarding.audit.AuditEventView;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.TimelineService;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.security.SecurityTestBase;
import co.ara.onboarding.support.RecordingEmailSender;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WriteScope;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static co.ara.onboarding.support.PostgresTestBase.ownerJdbcForSupport;
import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec 8: a staff member nudges the customer contact about an open document request. */
class CustomerReminderTest extends SecurityTestBase {

    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired RecordingEmailSender emails;
    @Autowired DocumentRequestService requestService;
    @Autowired TimelineService timeline;
    @Autowired AppUserRepository users;
    @Autowired DocumentRequestRepository requestRepository;
    @Autowired DocumentRepository documentRepository;

    private String slug;
    private UUID tenant;
    private AppUser actor;
    private UUID caseId;
    private UUID customerId;
    private String contactEmail;
    private UUID contactId;

    @BeforeEach
    void seed() {
        slug = "doc-remind-" + Uuid7.generate();
        tenant = fixture.createTenant(slug);
        actor = fixture.createUserWithPassword(tenant, "actor+" + Uuid7.generate() + "@remind.example", "long-enough-password");
        contactEmail = "contact+" + Uuid7.generate() + "@customer.example";
        fixture.runAs(tenant, () -> {
            roles.assignRole(actor.getId(), roles.createRole("Reminder Actor " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL,
                    PermissionKeys.CONTACT_VIEW, Scope.ALL,
                    PermissionKeys.CASE_VIEW, Scope.ALL,
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL)));
            Case c = journey.newCase(tenant);
            caseId = c.getId();
            customerId = c.getCustomerId();
            contactId = fixture.createContact(tenant, customerId, contactEmail);
        });
    }

    private String base() { return "/api/t/" + slug; }

    private UUID openRequest(UUID onCase, UUID contact) throws Exception {
        String body = "{\"category\":\"CONTRACT\",\"description\":\"Signed MSA\""
                + (contact == null ? "" : ",\"requestedOfContactId\":\"" + contact + "\"") + "}";
        String json = mvc.perform(as(post(base() + "/cases/" + onCase + "/document-requests"), actor)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    /** An OPEN request written directly, for stages whose write scope refuses the admin actor. */
    private UUID seedRequest(UUID onCase, UUID contact) {
        UUID[] id = new UUID[1];
        fixture.runAs(tenant, () -> {
            DocumentRequest dr = new DocumentRequest();
            dr.setId(Uuid7.generate());
            dr.setTenantId(tenant);
            dr.setCaseId(onCase);
            dr.setRequestedOfContactId(contact);
            dr.setCategory(DocumentCategory.OTHER);
            dr.setDescription("Seeded");
            dr.setStatus(DocumentRequestStatus.OPEN);
            dr.setRequestedBy(actor.getId());
            dr.setRequestedAt(java.time.Instant.now());
            id[0] = requestRepository.saveAndFlush(dr).getId();
        });
        return id[0];
    }

    private ResultActions remind(AppUser by, UUID id) throws Exception {
        return mvc.perform(as(post(base() + "/document-requests/" + id + "/remind"), by));
    }

    private int remindersSent(UUID id) {
        return ownerJdbcForSupport().queryForObject(
                "select reminders_sent from document_request where id = ?", Integer.class, id);
    }

    /** A TEAM-scoped document.request holder in a team the given user ids need not share. */
    private AppUser teamHolder(UUID team) {
        AppUser u = fixture.createUserWithPassword(tenant, "team+" + Uuid7.generate() + "@remind.example", "long-enough-password");
        fixture.runAs(tenant, () -> {
            fixture.addToTeam(tenant, u.getId(), team);
            roles.assignRole(u.getId(), roles.createRole("Team Reminder " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.TEAM,
                    PermissionKeys.CONTACT_VIEW, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL)));
        });
        return u;
    }

    @Test
    void remindingEmailsTheContactAndCounts() throws Exception {
        UUID id = openRequest(caseId, contactId);
        remind(actor, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.remindersSent").value(1))
                .andExpect(jsonPath("$.lastRemindedAt").exists());
        var mail = emails.lastTo(contactEmail);
        assertThat(mail).isPresent();
        assertThat(mail.get().body()).contains("Signed MSA");
        assertThat(mail.get().body()).doesNotContain(actor.getEmail());
    }

    @Test
    void aSecondReminderWithin24HoursIs409ThenAllowedAfter() throws Exception {
        UUID id = openRequest(caseId, contactId);
        remind(actor, id).andExpect(status().isOk());
        remind(actor, id).andExpect(status().isConflict());
        clock.advance(Duration.ofHours(25));
        remind(actor, id).andExpect(status().isOk()).andExpect(jsonPath("$.remindersSent").value(2));
    }

    @Test
    void aRequestWithNoContactIs422() throws Exception {
        remind(actor, openRequest(caseId, null)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aRetiredContactIs422() throws Exception {
        UUID id = openRequest(caseId, contactId);
        ownerJdbcForSupport().update("update customer_contact set status = 'INACTIVE' where id = ?", contactId);
        remind(actor, id).andExpect(status().isUnprocessableEntity());
        assertThat(emails.lastTo(contactEmail)).isEmpty();
        assertThat(remindersSent(id)).isZero();
    }

    @Test
    void aFulfilledOrWithdrawnRequestIs422() throws Exception {
        UUID withdrawn = openRequest(caseId, contactId);
        mvc.perform(as(post(base() + "/document-requests/" + withdrawn + "/withdraw"), actor)
                        .contentType("application/json").content("{\"reason\":\"no longer needed\"}"))
                .andExpect(status().isOk());
        remind(actor, withdrawn).andExpect(status().isUnprocessableEntity());

        UUID fulfilled = openRequest(caseId, contactId);
        UUID documentId = Uuid7.generate();
        fixture.runAs(tenant, () -> {
            Document d = new Document();
            d.setId(documentId);
            d.setTenantId(tenant);
            d.setCaseId(caseId);
            d.setCustomerId(customerId);
            d.setName("Signed MSA");
            d.setCategory(DocumentCategory.OTHER);
            d.setVisibilityTier(VisibilityTier.COMPANY_SHARED);
            d.setStatus(DocumentStatus.ACTIVE);
            d.setUploadedBy(actor.getId());
            documentRepository.saveAndFlush(d);
        });
        mvc.perform(as(post(base() + "/document-requests/" + fulfilled + "/fulfil"), actor)
                        .contentType("application/json").content("{\"documentId\":\"" + documentId + "\"}"))
                .andExpect(status().isOk());
        remind(actor, fulfilled).andExpect(status().isUnprocessableEntity());
        assertThat(emails.lastTo(contactEmail)).isEmpty();
    }

    @Test
    void anOutOfScopeOrForeignRequestIs404() throws Exception {
        // Created on a case owned by one team...
        UUID[] ownTeam = new UUID[1];
        UUID[] teamCase = new UUID[1];
        UUID[] teamContact = new UUID[1];
        UUID[] otherTeam = new UUID[1];
        fixture.runAs(tenant, () -> {
            ownTeam[0] = fixture.createTeam(tenant, "Own Team " + Uuid7.generate());
            otherTeam[0] = fixture.createTeam(tenant, "Other Team " + Uuid7.generate());
            Case c = journey.newCase(tenant, null, null, ownTeam[0]);
            teamCase[0] = c.getId();
            teamContact[0] = fixture.createContact(tenant, c.getCustomerId(), "tc+" + Uuid7.generate() + "@customer.example");
        });
        UUID id = openRequest(teamCase[0], teamContact[0]);
        // ...a TEAM-scoped holder in a different team (the narrowest scope) cannot reach it.
        remind(teamHolder(otherTeam[0]), id).andExpect(status().isNotFound());
        assertThat(remindersSent(id)).isZero();

        // A foreign tenant's actor, and an unknown id.
        String otherSlug = "doc-remind-other-" + Uuid7.generate();
        UUID otherTenant = fixture.createTenant(otherSlug);
        AppUser foreign = fixture.createUserWithPassword(otherTenant, "f+" + Uuid7.generate() + "@x.example", "long-enough-password");
        fixture.runAs(otherTenant, () -> roles.assignRole(foreign.getId(), roles.createRole("F " + Uuid7.generate(), "",
                Map.of(PermissionKeys.DOCUMENT_REQUEST, Scope.ALL))));
        mvc.perform(as(post("/api/t/" + otherSlug + "/document-requests/" + id + "/remind"), foreign))
                .andExpect(status().isNotFound());
        remind(actor, Uuid7.generate()).andExpect(status().isNotFound());
    }

    @Test
    void aPortalContactCannotRemind() throws Exception {
        UUID id = openRequest(caseId, contactId);
        UUID portalId = fixture.createPortalUserForContact(tenant, customerId, "portal+" + Uuid7.generate() + "@customer.example");
        AppUser portal = fixture.runAsReturning(tenant, () -> users.findById(portalId).orElseThrow());
        remind(portal, id).andExpect(status().isForbidden());
        assertThat(remindersSent(id)).isZero();
    }

    @Test
    void theWriteScopeGuardApplies() throws Exception {
        UUID[] ids = new UUID[2];
        UUID[] team = new UUID[1];
        fixture.runAs(tenant, () -> {
            team[0] = fixture.createTeam(tenant, "Fixture Team " + Uuid7.generate());
            UUID owner = fixture.createUser(tenant, "owner+" + Uuid7.generate() + "@remind.example");
            var stage = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Restricted Stage", null, false, true, true, null,
                    WriteScope.OWNER_ONLY, null, null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))), List.of());
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(stage), List.of(), 0L));
            UUID cust = fixture.createCustomer(tenant, "Remind WS Co " + Uuid7.generate(), owner, null, team[0]);
            ids[0] = cases.create(new CreateCaseRequest(cust, journey.templateOf(versionId),
                    "WS Case " + Uuid7.generate(), Map.of())).id();
            ids[1] = fixture.createContact(tenant, cust, "ws+" + Uuid7.generate() + "@customer.example");
        });
        UUID id = seedRequest(ids[0], ids[1]);
        remind(teamHolder(team[0]), id).andExpect(status().isForbidden());
        assertThat(remindersSent(id)).isZero();
    }

    @Test
    void theReminderIsOnTheTimeline() throws Exception {
        UUID id = openRequest(caseId, contactId);
        remind(actor, id).andExpect(status().isOk());
        fixture.runAsUser(tenant, actor.getId(), () ->
                assertThat(timeline.forCase(caseId, Pageable.ofSize(50)).map(AuditEventView::action).getContent())
                        .contains("document_request.reminded"));
    }

    @Test
    void aRolledBackReminderSendsNoEmail() throws Exception {
        UUID id = openRequest(caseId, contactId);
        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor.getId(), () -> {
            requestService.remind(id);
            throw new IllegalStateException("force a rollback after the reminder was written");
        })).hasMessageContaining("force a rollback");
        assertThat(emails.lastTo(contactEmail)).isEmpty();
        assertThat(remindersSent(id)).isZero();
    }

    @Test
    void twoConcurrentRemindersSendExactlyOne() throws Exception {
        UUID id = openRequest(caseId, contactId);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = List.of(1, 2).stream().map(i -> pool.submit(() -> {
                go.await();
                try {
                    fixture.runAsUser(tenant, actor.getId(), () -> requestService.remind(id));
                    return true;
                } catch (IllegalStateException e) {
                    return false;
                }
            })).toList();
            go.countDown();
            int wins = 0;
            for (Future<Boolean> f : results) if (f.get()) wins++;
            assertThat(wins).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(remindersSent(id)).isEqualTo(1);
        assertThat(emails.lastTo(contactEmail)).isPresent();
    }
}
