package co.ara.onboarding.platform.storage;

/**
 * Thrown by {@link BlobStore#open(String)} when the storage key is unknown to
 * the adapter. {@code open} never returns null on a miss -- this is the only
 * signal.
 */
public class BlobNotFoundException extends RuntimeException {

    public BlobNotFoundException(String storageKey) {
        super("No blob stored under key: " + storageKey);
    }

    public BlobNotFoundException(String storageKey, Throwable cause) {
        super("No blob stored under key: " + storageKey, cause);
    }
}
