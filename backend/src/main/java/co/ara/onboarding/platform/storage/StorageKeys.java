package co.ara.onboarding.platform.storage;

import co.ara.onboarding.platform.Uuid7;

/**
 * Key generation shared by every {@link BlobStore} adapter. Extracted from
 * {@link LocalFsBlobStore} in Task 5 so a second adapter (S3) could not drift
 * into a differently-shaped key -- two adapters each individually satisfying
 * {@link BlobStoreContract} while producing incompatible key shapes would be a
 * latent migration problem the contract test cannot see, since it only ever
 * exercises one adapter at a time.
 */
final class StorageKeys {

    private StorageKeys() {
    }

    /**
     * A UUIDv7 rendered lowercase hexadecimal (no dashes), sharded two levels.
     * UUIDv7 rather than SecureRandom because a storage key need only be
     * unique, not unpredictable -- access is always mediated by the
     * application (spec 7.3), never by key secrecy. CLAUDE.md's rule is that
     * values needing unpredictability use SecureRandom; this is explicitly
     * not one of them.
     */
    static String newKey() {
        String flat = Uuid7.generate().toString().replace("-", "");
        return flat.substring(0, 2) + "/" + flat.substring(2, 4) + "/" + flat;
    }
}
