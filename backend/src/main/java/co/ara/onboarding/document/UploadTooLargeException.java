package co.ara.onboarding.document;

/**
 * Task 7's hardening ruling (spec §2.3/§7.6): the caller's DECLARED
 * {@code sizeBytes} exceeds {@code app.storage.max-upload-bytes}. Refused
 * before any stream is touched at all -- no prefix read, no sniff, no blob
 * write, no row -- which is why this is checked against the declared length
 * rather than bytes actually read: trusting the declared length for this one
 * ceiling is exactly what {@link co.ara.onboarding.platform.storage.BlobStore#put}'s
 * own contract already allows ("adapters may use it to choose a transfer
 * strategy but must not trust it for correctness" -- this ceiling is the one
 * place declared length IS the correctness check, deliberately).
 *
 * Mapped to 413 by the controller (Task 22).
 */
public class UploadTooLargeException extends RuntimeException {

    public UploadTooLargeException(long sizeBytes, long maxBytes) {
        super("Upload of " + sizeBytes + " bytes exceeds the " + maxBytes
                + " byte ceiling (app.storage.max-upload-bytes)");
    }
}
