package co.ara.onboarding.document;

import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 8: RlsCoverageTest already proves tenant_id + RLS + FORCE on document,
 * document_version, document_share, document_case_link and document_request.
 * This test covers what that meta-test does not: the CHECK constraints that
 * encode domain rules at the database, document_version's content-immutability
 * trigger (and that its review columns remain genuinely updatable through it,
 * not just documented as such), and that DELETE is denied on document itself
 * (the general default-privilege revoke in V2_1/V5_1 is proven generically
 * there; this is the same live, table-specific proof AuditAppendOnlyTest and
 * PlanSnapshotImmutabilityTest already give their own append-only tables).
 *
 * Setup writes go through raw JdbcTemplate rather than entities/repositories,
 * the same shape ProgrammeSchemaTest and PlanSnapshotImmutabilityTest already
 * use for schema-only proofs -- this test was written before the module's own
 * entities existed (Task 8's own TDD step 2).
 */
class DocumentSchemaTest extends PostgresTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;

    private UUID tenant;
    private UUID caseId;
    private UUID customerId;
    private UUID uploadedBy;

    @BeforeEach
    void seedTenantCaseAndUser() {
        tenant = fixture.createTenant("doc-schema-" + Uuid7.generate());
        var caseRef = new AtomicReference<UUID>();
        var customerRef = new AtomicReference<UUID>();
        var userRef = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            caseRef.set(c.getId());
            customerRef.set(c.getCustomerId());
            userRef.set(fixture.createUser(tenant, "uploader@fixture.test"));
        });
        caseId = caseRef.get();
        customerId = customerRef.get();
        uploadedBy = userRef.get();
    }

    @Test
    void aSecondVersionWithTheSameNumberIsRejected() {
        UUID documentId = insertDocument("COMPANY_SHARED", null);
        insertVersion(documentId, 1);

        assertThatThrownBy(() -> insertVersion(documentId, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anUnknownVisibilityTierIsRejected() {
        assertThatThrownBy(() -> insertDocument("BOGUS_TIER", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aContactOnlyDocumentWithNoOwnerContactIsRejected() {
        assertThatThrownBy(() -> insertDocument("CONTACT_ONLY", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** The positive counterpart: document_owner_ck refuses only the missing-owner case. */
    @Test
    void aContactOnlyDocumentWithAnOwnerContactIsAccepted() {
        var contactRef = new AtomicReference<UUID>();
        fixture.runAs(tenant, () ->
                contactRef.set(fixture.createContact(tenant, customerId, "contact@fixture.test")));

        assertThatNoException().isThrownBy(() -> insertDocument("CONTACT_ONLY", contactRef.get()));
    }

    @Test
    void updatingAnImmutableVersionColumnIsRejected() {
        UUID documentId = insertDocument("COMPANY_SHARED", null);
        UUID versionId = insertVersion(documentId, 1);

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> jdbc.update(
                        "UPDATE document_version SET storage_key = ? WHERE id = ?",
                        "tampered-key", versionId)))
                .hasStackTraceContaining("document_version content is immutable once written");
    }

    /**
     * The positive counterpart the brief's own comment insists on: a blanket
     * UPDATE revoke would have blocked this too, so the trigger -- not a grant --
     * is what must be proven to let this through while refusing the content
     * columns above.
     */
    @Test
    void theReviewOutcomeOfAVersionRemainsUpdatable() {
        UUID documentId = insertDocument("COMPANY_SHARED", null);
        UUID versionId = insertVersion(documentId, 1);

        assertThatNoException().isThrownBy(() -> fixture.runAs(tenant, () -> jdbc.update("""
                UPDATE document_version
                SET review_status = 'APPROVED', reviewed_by = ?, reviewed_at = now(), review_note = ?
                WHERE id = ?
                """, uploadedBy, "looks fine", versionId)));
    }

    @Test
    void deletingADocumentIsDeniedAtTheDatabase() {
        UUID documentId = insertDocument("COMPANY_SHARED", null);

        assertThatThrownBy(() -> fixture.runAs(tenant, () ->
                jdbc.update("DELETE FROM document WHERE id = ?", documentId)))
                .hasStackTraceContaining("permission denied for table document");
    }

    // ---- helpers ------------------------------------------------------------

    /** Self-wraps in {@link TenantFixture#runAs}: called at the top of each test, never nested in another. */
    private UUID insertDocument(String visibilityTier, UUID ownerContactId) {
        UUID id = Uuid7.generate();
        Timestamp now = Timestamp.from(Instant.now(clock));
        fixture.runAs(tenant, () -> jdbc.update("""
                INSERT INTO document
                    (id, tenant_id, case_id, customer_id, name, category, visibility_tier,
                     owner_contact_id, status, uploaded_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'OTHER', ?, ?, 'ACTIVE', ?, ?, ?)
                """,
                id, tenant, caseId, customerId, "Fixture Document", visibilityTier,
                ownerContactId, uploadedBy, now, now));
        return id;
    }

    /** Self-wraps in {@link TenantFixture#runAs}, same reasoning as {@link #insertDocument}. */
    private UUID insertVersion(UUID documentId, int versionNo) {
        UUID id = Uuid7.generate();
        Timestamp now = Timestamp.from(Instant.now(clock));
        fixture.runAs(tenant, () -> jdbc.update("""
                INSERT INTO document_version
                    (id, tenant_id, document_id, version_no, storage_key, size_bytes, content_type,
                     sha256, review_status, uploaded_by, uploaded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """,
                id, tenant, documentId, versionNo, "documents/" + id, 1024L, "application/pdf",
                "0".repeat(64), uploadedBy, now));
        return id;
    }
}
