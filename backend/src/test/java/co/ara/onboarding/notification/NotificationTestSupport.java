package co.ara.onboarding.notification;

import co.ara.onboarding.document.DocumentCategory;
import co.ara.onboarding.document.DocumentRequest;
import co.ara.onboarding.document.DocumentRequestRepository;
import co.ara.onboarding.document.DocumentRequestStatus;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Arrange/assert helpers for the notification tests. Reads go through the owner connection (assertions only). */
@Component
public class NotificationTestSupport {

    private final OutboxWriter outbox;
    private final TenantFixture fixture;
    private final JourneyFixtures journey;
    private final DocumentRequestRepository requests;

    NotificationTestSupport(OutboxWriter outbox, TenantFixture fixture, JourneyFixtures journey,
                            DocumentRequestRepository requests) {
        this.outbox = outbox;
        this.fixture = fixture;
        this.journey = journey;
        this.requests = requests;
    }

    public record Arranged(UUID tenant, UUID caseId, UUID requestId, UUID contactId, String contactEmail) {}

    /** A tenant with one case and one OPEN document request naming an ACTIVE contact. */
    public Arranged openRequestWithContact(String slug) {
        UUID tenant = fixture.createTenant(slug);
        String email = "contact+" + Uuid7.generate() + "@customer.example";
        UUID[] ids = new UUID[3];
        fixture.runAs(tenant, () -> {
            var c = journey.newCase(tenant);
            ids[0] = c.getId();
            ids[1] = fixture.createContact(tenant, c.getCustomerId(), email);
            DocumentRequest dr = new DocumentRequest();
            dr.setId(Uuid7.generate());
            dr.setTenantId(tenant);
            dr.setCaseId(c.getId());
            dr.setRequestedOfContactId(ids[1]);
            dr.setCategory(DocumentCategory.CONTRACT);
            dr.setDescription("Signed MSA");
            dr.setStatus(DocumentRequestStatus.OPEN);
            dr.setRequestedBy(fixture.createUser(tenant, "req+" + Uuid7.generate() + "@customer.example"));
            dr.setRequestedAt(java.time.Instant.now());
            ids[2] = requests.saveAndFlush(dr).getId();
        });
        return new Arranged(tenant, ids[0], ids[2], ids[1], email);
    }

    /** Must run inside fixture.runAs. */
    public UUID queueEmail(UUID tenant, UUID recipientUserId, String to, String subject) {
        return outbox.queue(new OutboxWriter.OutboxMessage(OutboxKind.NOTIFICATION, to, recipientUserId, null,
                null, null, subject, "body of " + subject, "/t/x/path"));
    }

    public List<Map<String, Object>> outbox(UUID tenant) {
        return PostgresTestBase.ownerJdbcForSupport().queryForList(
                "select * from email_outbox where tenant_id = ? order by created_at, id", tenant);
    }

    public List<Map<String, Object>> notifications(UUID tenant) {
        return PostgresTestBase.ownerJdbcForSupport().queryForList(
                "select * from notification where tenant_id = ? order by created_at, id", tenant);
    }
}
