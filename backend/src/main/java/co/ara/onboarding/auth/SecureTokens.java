package co.ara.onboarding.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Bearer-credential generation and hashing, shared by refresh tokens, invitations
 * and password resets.
 *
 * Extracted rather than duplicated: three copies of "generate randomness, hash it,
 * store the hash" is three chances for one of them to quietly use a weaker source
 * or forget to hash at all.
 *
 * Public rather than package-private since Task R1, because provisioning issues the
 * first tenant administrator's ACTIVATION invitation itself — it runs before any
 * actor exists, so it cannot go through the gated UserInvitationService. Widening
 * the visibility is the lesser evil: the alternative was a second SecureRandom in
 * provisioning, which is precisely the duplication this class exists to prevent.
 */
public final class SecureTokens {

    /**
     * SecureRandom, never Uuid7. These values must be unpredictable, not merely
     * unique — a UUIDv7 leaks its creation time and is guessable from a neighbouring
     * value, which is exactly what a credential must not be. 32 bytes = 256 bits.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureTokens() {}

    /** URL-safe and unpadded, so it survives being placed in an email link verbatim. */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Plain SHA-256, deliberately not a password hash. The input is 256 bits of
     * cryptographic randomness, so there is no dictionary to attack and no work
     * factor is needed — Argon2 here would make every refresh cost 64 MiB. 64 hex
     * characters, which is what the token_hash columns are sized for.
     */
    public static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }
}
