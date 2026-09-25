package co.ara.onboarding.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import co.ara.onboarding.workflow.AgreementRecordMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgreementContentHasherTest {

    private static final UUID S1 = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID S2 = UUID.fromString("01900000-0000-7000-8000-000000000002");
    private static final UUID C1 = UUID.fromString("01900000-0000-7000-8000-0000000000c1");
    private static final UUID U1 = UUID.fromString("01900000-0000-7000-8000-0000000000a1");

    private static final AgreementSnapshot.SignatorySnapshot A =
            new AgreementSnapshot.SignatorySnapshot(S1, SignatoryKind.CONTACT, C1, null, "Customer signatory", 0);
    private static final AgreementSnapshot.SignatorySnapshot B =
            new AgreementSnapshot.SignatorySnapshot(S2, SignatoryKind.INTERNAL, null, U1, "Provider signatory", 1);

    private static AgreementSnapshot snapshot(List<AgreementSnapshot.SignatorySnapshot> signatories) {
        return new AgreementSnapshot("Master Services Agreement", AgreementRecordMode.STRUCTURED_PLUS_FILE,
                LocalDate.of(2026, 10, 1), LocalDate.of(2027, 9, 30), null, 30, signatories);
    }

    @Test
    void canonicalJsonSortsKeysUsesIsoDatesAndSerialisesAbsentOptionalsAsNull() {
        assertThat(AgreementContentHasher.canonicalJson(snapshot(List.of(A))))
                .isEqualTo("{\"effectiveDate\":\"2026-10-01\",\"expiresAt\":\"2027-09-30\",\"name\":\"Master Services Agreement\","
                        + "\"noticePeriodDays\":30,\"recordMode\":\"STRUCTURED_PLUS_FILE\",\"renewalDate\":null,"
                        + "\"signatories\":[{\"contactId\":\"" + C1 + "\",\"displayRole\":\"Customer signatory\",\"id\":\"" + S1
                        + "\",\"kind\":\"CONTACT\",\"sortOrder\":0,\"userId\":null}]}");
    }

    @Test
    void signatoryInputOrderDoesNotChangeTheHash() {
        assertThat(AgreementContentHasher.contentSha256(snapshot(List.of(B, A)), null))
                .isEqualTo(AgreementContentHasher.contentSha256(snapshot(List.of(A, B)), null));
    }

    @Test
    void anyFieldChangeChangesTheHash() {
        var base = snapshot(List.of(A));
        var renamed = new AgreementSnapshot("MSA v2", base.recordMode(), base.effectiveDate(), base.expiresAt(),
                base.renewalDate(), base.noticePeriodDays(), base.signatories());
        assertThat(AgreementContentHasher.contentSha256(renamed, null))
                .isNotEqualTo(AgreementContentHasher.contentSha256(base, null));
    }

    @Test
    void theFileDigestIsPartOfTheIdentity() {
        var s = snapshot(List.of(A));
        String withFile = AgreementContentHasher.contentSha256(s, "a".repeat(64));
        assertThat(withFile).isNotEqualTo(AgreementContentHasher.contentSha256(s, null));
        assertThat(withFile).isNotEqualTo(AgreementContentHasher.contentSha256(s, "b".repeat(64)));
        assertThat(withFile).matches("[0-9a-f]{64}");
    }

    @Test
    void aQuoteOrNewlineInANameIsEscapedNotInjected() {
        var tricky = new AgreementSnapshot("A \"quoted\"\nname", AgreementRecordMode.STRUCTURED_ONLY,
                LocalDate.of(2026, 1, 1), null, null, null, List.of());
        assertThat(AgreementContentHasher.canonicalJson(tricky)).contains("\"name\":\"A \\\"quoted\\\"\\nname\"");
    }

    @Test
    void knownVectorPinsTheAlgorithm() {
        // Produced in Step 4 by an EXTERNAL sha256sum over canonicalJson(snapshot(List.of(A)))
        // followed by one NUL byte -- never by the class under test. A refactor that changes
        // key order, date format or the separator then fails here instead of silently re-hashing.
        assertThat(AgreementContentHasher.contentSha256(snapshot(List.of(A)), null))
                .isEqualTo(KNOWN_DIGEST);
    }

    private static final String KNOWN_DIGEST = "b3b2068f5fbc0103b25ca77892e072d4a2033f406407c871752da06a1d62d570";
}
