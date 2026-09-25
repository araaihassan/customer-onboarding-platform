package co.ara.onboarding.agreement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Spec 4.4.1 -- the ONE place content_sha256 is computed. Hand-built canonical JSON
 * rather than an ObjectMapper configuration: the bytes must stay stable across Jackson
 * upgrades and config drift, and a known-answer test pins them.
 *
 * content_sha256 = SHA-256( canonical_json || 0x00 || (document_sha256 ?? "") ), lowercase hex.
 */
public final class AgreementContentHasher {

    private AgreementContentHasher() {}

    public static String contentSha256(AgreementSnapshot s, String documentSha256OrNull) {
        MessageDigest digest = sha256();
        digest.update(canonicalJson(s).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update((documentSha256OrNull == null ? "" : documentSha256OrNull).getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String canonicalJson(AgreementSnapshot s) {
        List<AgreementSnapshot.SignatorySnapshot> ordered = s.signatories().stream()
                .sorted(Comparator.comparingInt(AgreementSnapshot.SignatorySnapshot::sortOrder)
                        .thenComparing(AgreementSnapshot.SignatorySnapshot::id))
                .toList();
        // Keys are written in lexicographic order by hand -- that IS the canonicalisation.
        StringBuilder b = new StringBuilder("{");
        field(b, "effectiveDate", date(s.effectiveDate())).append(',');
        field(b, "expiresAt", date(s.expiresAt())).append(',');
        field(b, "name", str(s.name())).append(',');
        field(b, "noticePeriodDays", s.noticePeriodDays() == null ? "null" : s.noticePeriodDays().toString()).append(',');
        field(b, "recordMode", str(s.recordMode().name())).append(',');
        field(b, "renewalDate", date(s.renewalDate())).append(',');
        b.append("\"signatories\":[");
        for (int i = 0; i < ordered.size(); i++) {
            var sig = ordered.get(i);
            if (i > 0) b.append(',');
            b.append('{');
            field(b, "contactId", uuid(sig.contactId())).append(',');
            field(b, "displayRole", str(sig.displayRole())).append(',');
            field(b, "id", uuid(sig.id())).append(',');
            field(b, "kind", str(sig.kind().name())).append(',');
            field(b, "sortOrder", Integer.toString(sig.sortOrder())).append(',');
            field(b, "userId", uuid(sig.userId()));
            b.append('}');
        }
        return b.append("]}").toString();
    }

    private static StringBuilder field(StringBuilder b, String key, String jsonValue) {
        return b.append('"').append(key).append("\":").append(jsonValue);
    }

    private static String date(LocalDate d) { return d == null ? "null" : "\"" + d + "\""; }   // ISO yyyy-MM-dd
    private static String uuid(UUID u) { return u == null ? "null" : "\"" + u + "\""; }

    private static String str(String s) {
        if (s == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.append('"').toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
