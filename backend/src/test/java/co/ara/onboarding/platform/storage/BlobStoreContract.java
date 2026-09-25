package co.ara.onboarding.platform.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One contract, run against every adapter. Task 5's S3BlobStoreTest extends this
 * class and supplies a MinIO-backed store; LocalFsBlobStoreTest supplies a
 * temp-directory-backed one. Any behaviour asserted here must hold for both, or
 * the port is leaking implementation differences into its callers.
 */
public abstract class BlobStoreContract {

    protected abstract BlobStore store();

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(InputStream in) throws Exception {
        try (in) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    @Test
    void putThenOpenRoundTripsTheExactBytes() throws Exception {
        String body = "hello éàü 📄";  // non-ASCII on purpose
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        String key = store().put(new ByteArrayInputStream(raw), raw.length, "text/plain");

        assertThat(read(store().open(key))).isEqualTo(body);
    }

    @Test
    void everyPutReturnsADistinctKeyEvenForIdenticalContent() {
        String a = store().put(bytes("same"), 4, "text/plain");
        String b = store().put(bytes("same"), 4, "text/plain");

        assertThat(a).isNotEqualTo(b);
    }

    /**
     * Keys are generated, never derived from caller input. Nothing the caller
     * supplies -- filename, document name, tenant string -- may reach a
     * filesystem path or an object name (spec 7.2).
     */
    @Test
    void keysContainNoPathTraversalAndNoCallerSuppliedText() {
        String key = store().put(bytes("x"), 1, "text/plain");

        assertThat(key).doesNotContain("..").doesNotContain("\\");
        assertThat(key).matches("[A-Za-z0-9/_-]+");
    }

    @Test
    void existsIsTrueForAStoredKeyAndFalseOtherwise() {
        String key = store().put(bytes("x"), 1, "text/plain");

        assertThat(store().exists(key)).isTrue();
        assertThat(store().exists("definitely-not-a-key")).isFalse();
    }

    @Test
    void openingAnUnknownKeyThrowsRatherThanReturningEmpty() {
        assertThatThrownBy(() -> store().open("definitely-not-a-key"))
                .isInstanceOf(BlobNotFoundException.class);
    }

    /**
     * The port has NO delete, deliberately (spec 7.1). This test is the guard:
     * it fails to compile if someone adds one, which is the point.
     */
    @Test
    void thePortExposesNoDeleteOperation() {
        assertThat(BlobStore.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("delete", "remove", "purge");
    }
}
