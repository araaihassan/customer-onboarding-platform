package co.ara.onboarding.document;

/**
 * Task 7's hardening ruling (spec §2.3/§7.6): the content type SNIFFED from
 * the uploaded bytes themselves -- never the caller's declared Content-Type
 * -- is not on {@link ContentSniffGuard}'s allowlist for the document's
 * category. This is what closes "a .pdf that is really an HTML page": the
 * check runs against {@link org.apache.tika.Tika#detect(byte[])}'s own
 * verdict over a prefix of the real bytes, before {@code BlobStore.put} or
 * any row write.
 *
 * Mapped to 422 by the controller (Task 22).
 */
public class UnacceptableContentTypeException extends RuntimeException {

    public UnacceptableContentTypeException(DocumentCategory category, String sniffedType) {
        super("Uploaded content sniffed as \"" + sniffedType
                + "\", which is not an accepted type for category " + category);
    }
}
