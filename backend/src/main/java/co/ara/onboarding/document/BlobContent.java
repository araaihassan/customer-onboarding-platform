package co.ara.onboarding.document;

import java.io.InputStream;

/**
 * The bytes of one {@link DocumentVersion} plus what a controller needs to
 * serve them correctly -- {@link DocumentContentService#open}'s return shape.
 *
 * {@code filename} is {@link Document#getName()} -- the schema carries no
 * separate filename column, so the document's own name IS the filename
 * basis. {@code contentType} is the SNIFFED value
 * {@link DocumentService#captureContent} captured at upload time, never the
 * uploader's declared one.
 *
 * {@code sizeBytes}, by contrast, is NOT sniffed or otherwise verified: it is
 * the caller's DECLARED length from upload time, persisted unchanged onto
 * {@link DocumentVersion#getSizeBytes()} -- {@code captureContent} returns
 * only a storage key, a sniffed content type and a SHA-256, never a
 * recomputed size, and {@link BlobStore#put} itself documents that it "must
 * not trust [the declared length] for correctness." A future controller
 * (Task 22) must not build {@code Content-Length} trust on top of this field
 * as though it had been verified -- it has not.
 *
 * Task 7's ruling requires {@code Content-Disposition: attachment} on every
 * document response, with the real filename, never inline -- but this module
 * has no controller yet (Task 22 builds it). Setting that literal HTTP
 * header is the controller's job; this record only guarantees the three
 * fields it needs to do so correctly.
 */
public record BlobContent(InputStream content, String contentType, long sizeBytes, String filename) {

    /**
     * The document's real name, as {@link Document#getName()} carries it --
     * free-form, user-supplied text, constrained only by {@code @NotBlank}
     * (no length limit, no character restriction). A controller must NOT
     * string-concatenate this into a raw {@code Content-Disposition} header
     * value (e.g. {@code "attachment; filename=\"" + filename + "\""}): an
     * unescaped quote or a CR/LF in a document's name would break out of the
     * quoted parameter or inject a second header. Use a real encoder instead
     * -- Spring's {@code ContentDisposition.builder("attachment")
     * .filename(name, StandardCharsets.UTF_8)} -- never hand-rolled string
     * concatenation.
     */
    public String filename() {
        return filename;
    }
}
