package co.ara.onboarding.agreement;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RlsCoverageTest proves tenant_id + RLS + FORCE on every new table. This covers the
 * domain rules the migration encodes: one live agreement per requirement, the CHECKs,
 * and -- run as onboarding_app, the role the application really connects as -- that the
 * three append-only tables refuse UPDATE and DELETE.
 *
 * Setup writes for the four fill-in cases go through raw JdbcTemplate rather than
 * entities/repositories, the same shape document.DocumentSchemaTest already uses
 * for schema-only proofs -- AgreementVersion/AgreementVersionReview/AgreementSignature
 * carry no setters at all (append-only, Step 4's immutable shape), so a raw INSERT
 * is the only way to build an offending row in the first place.
 */
class AgreementSchemaTest extends PostgresTestBase {

    private static final String HASH = "0".repeat(64);

    @Autowired TenantFixture fixture;
    @Autowired AgreementTestSupport support;
    @Autowired AgreementRepository agreements;
    @Autowired JdbcTemplate jdbc; // connects as onboarding_app in tests -- see PostgresTestBase

    @Test
    void aSecondLiveAgreementForTheSameRequirementIsRejected() {
        UUID tenant = fixture.createTenant("agr-schema-live-" + Uuid7.generate());
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            Agreement first = support.draftAgreementRow(tenant);
            support.draftAgreementRowFor(tenant, first.getCaseId(), first.getRequirementId());
        })).isInstanceOf(DataIntegrityViolationException.class)
           .hasMessageContaining("agreement_live_per_requirement_uq");
    }

    @Test
    void aCancelledAgreementDoesNotBlockItsReplacement() {
        UUID tenant = fixture.createTenant("agr-schema-repl-" + Uuid7.generate());
        fixture.runAs(tenant, () -> {
            Agreement first = support.draftAgreementRow(tenant);
            first.setStatus(AgreementStatus.CANCELLED);
            first.setCancelReason("terms changed");
            agreements.saveAndFlush(first);
            Agreement second = support.draftAgreementRowFor(tenant, first.getCaseId(), first.getRequirementId());
            assertThat(second.getId()).isNotEqualTo(first.getId());
        });
    }

    @Test
    void aCancelledAgreementWithoutAReasonIsRejected() {
        UUID tenant = fixture.createTenant("agr-schema-reason-" + Uuid7.generate());
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            a.setStatus(AgreementStatus.CANCELLED);
            agreements.saveAndFlush(a);
        })).isInstanceOf(DataIntegrityViolationException.class)
           .hasMessageContaining("agreement_cancel_reason_ck");
    }

    @Test
    void aSignatoryWithBothAContactAndAUserIsRejected() {
        UUID tenant = fixture.createTenant("agr-schema-signatory-" + Uuid7.generate());
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            UUID contactId = fixture.createContact(tenant, a.getCustomerId(), "signatory-contact+" + Uuid7.generate() + "@fixture.test");
            UUID userId = fixture.createUser(tenant, "signatory-user+" + Uuid7.generate() + "@fixture.test");
            insertSignatory(tenant, a.getId(), "CONTACT", contactId, userId);
        })).isInstanceOf(DataIntegrityViolationException.class)
           .hasMessageContaining("agreement_signatory_party_ck");
    }

    @Test
    void aStructuredOnlyVersionCarryingAFileIsRejected() {
        UUID tenant = fixture.createTenant("agr-schema-version-file-1-" + Uuid7.generate());
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            // document_version_id stays NULL -- the CHECK fails purely on
            // record_mode vs document_sha256, so no real document_version row
            // (and no cross-module FK) is needed to isolate this constraint.
            insertVersion(tenant, a.getId(), 1, "STRUCTURED_ONLY", null, HASH, a.getOwnerUserId());
        })).isInstanceOf(DataIntegrityViolationException.class)
           .hasMessageContaining("agreement_version_file_ck");
    }

    @Test
    void aFileIncludingVersionWithoutAFileIsRejected() {
        UUID tenant = fixture.createTenant("agr-schema-version-file-2-" + Uuid7.generate());
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            insertVersion(tenant, a.getId(), 1, "FILE_BACKED", null, null, a.getOwnerUserId());
        })).isInstanceOf(DataIntegrityViolationException.class)
           .hasMessageContaining("agreement_version_file_ck");
    }

    @Test
    void agreementVersionRefusesUpdateAndDeleteAsTheApplicationRole() {
        UUID tenant = fixture.createTenant("agr-schema-version-grant-" + Uuid7.generate());
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            UUID versionId = insertVersion(tenant, a.getId(), 1, "STRUCTURED_ONLY", null, null, a.getOwnerUserId());

            // hasStackTraceContaining, not hasMessageContaining: JdbcTemplate wraps the
            // driver error in a BadSqlGrammarException whose own message is generic,
            // so the Postgres text only appears on the cause -- the audit.AuditAppendOnlyTest
            // / document.DocumentSchemaTest mechanism, copied rather than invented.
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE agreement_version SET content_sha256 = ? WHERE id = ?", HASH, versionId))
                    .hasStackTraceContaining("permission denied for table agreement_version");

            assertThatThrownBy(() -> jdbc.update("DELETE FROM agreement_version WHERE id = ?", versionId))
                    .hasStackTraceContaining("permission denied for table agreement_version");
        });
    }

    @Test
    void agreementVersionReviewRefusesUpdateAndDeleteAsTheApplicationRole() {
        UUID tenant = fixture.createTenant("agr-schema-review-grant-" + Uuid7.generate());
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            UUID versionId = insertVersion(tenant, a.getId(), 1, "STRUCTURED_ONLY", null, null, a.getOwnerUserId());
            UUID reviewId = insertReview(tenant, versionId, "APPROVE", null, a.getOwnerUserId());

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE agreement_version_review SET decision = 'REJECT' WHERE id = ?", reviewId))
                    .hasStackTraceContaining("permission denied for table agreement_version_review");

            assertThatThrownBy(() -> jdbc.update("DELETE FROM agreement_version_review WHERE id = ?", reviewId))
                    .hasStackTraceContaining("permission denied for table agreement_version_review");
        });
    }

    @Test
    void agreementSignatureRefusesUpdateAndDeleteAsTheApplicationRole() {
        UUID tenant = fixture.createTenant("agr-schema-signature-grant-" + Uuid7.generate());
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            UUID versionId = insertVersion(tenant, a.getId(), 1, "STRUCTURED_ONLY", null, null, a.getOwnerUserId());
            UUID signatoryId = insertSignatory(tenant, a.getId(), "INTERNAL", null, a.getOwnerUserId());
            UUID signatureId = insertSignature(tenant, a.getId(), signatoryId, versionId, a.getOwnerUserId());

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE agreement_signature SET method = 'tampered' WHERE id = ?", signatureId))
                    .hasStackTraceContaining("permission denied for table agreement_signature");

            assertThatThrownBy(() -> jdbc.update("DELETE FROM agreement_signature WHERE id = ?", signatureId))
                    .hasStackTraceContaining("permission denied for table agreement_signature");
        });
    }

    @Test
    void deletingAnAgreementIsDeniedAtTheDatabase() {
        UUID tenant = fixture.createTenant("agr-schema-delete-" + Uuid7.generate());
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM agreement WHERE id = ?", a.getId()))
                    .hasStackTraceContaining("permission denied for table agreement");
        });
    }

    // ---- helpers --------------------------------------------------------------
    // Each self-wraps in the caller's own runAs; never call these outside one.

    private UUID insertSignatory(UUID tenant, UUID agreementId, String kind, UUID contactId, UUID userId) {
        UUID id = Uuid7.generate();
        jdbc.update("""
                INSERT INTO agreement_signatory
                    (id, tenant_id, agreement_id, kind, contact_id, user_id, display_role, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenant, agreementId, kind, contactId, userId, "Fixture Signatory", 1);
        return id;
    }

    private UUID insertVersion(UUID tenant, UUID agreementId, int versionNumber, String recordMode,
                                UUID documentVersionId, String documentSha256, UUID submittedBy) {
        UUID id = Uuid7.generate();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("""
                INSERT INTO agreement_version
                    (id, tenant_id, agreement_id, version_number, record_mode, submitted_by, submitted_at,
                     last_edited_by, structured_snapshot, document_version_id, document_sha256, content_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """,
                id, tenant, agreementId, versionNumber, recordMode, submittedBy, now,
                submittedBy, "{}", documentVersionId, documentSha256, HASH);
        return id;
    }

    private UUID insertReview(UUID tenant, UUID agreementVersionId, String decision, String reason, UUID reviewerId) {
        UUID id = Uuid7.generate();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("""
                INSERT INTO agreement_version_review
                    (id, tenant_id, agreement_version_id, decision, reviewer_id, reviewed_at, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenant, agreementVersionId, decision, reviewerId, now, reason);
        return id;
    }

    private UUID insertSignature(UUID tenant, UUID agreementId, UUID signatoryId, UUID agreementVersionId, UUID recordedBy) {
        UUID id = Uuid7.generate();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("""
                INSERT INTO agreement_signature
                    (id, tenant_id, agreement_id, signatory_id, agreement_version_id, signed_content_sha256,
                     signed_on, method, recorded_by, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenant, agreementId, signatoryId, agreementVersionId, HASH,
                java.sql.Date.valueOf(LocalDate.now(clock)), "Wet signature, scanned", recordedBy, now);
        return id;
    }
}
