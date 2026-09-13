package co.ara.onboarding.document;

import java.io.InputStream;

/**
 * The bytes of one {@link DocumentVersion} plus what a controller needs to
 * serve them correctly -- {@link DocumentContentService#open}'s return shape.
 *
 * {@code filename} is {@link Document#getName()} -- the schema carries no
 * separate filename column, so the document's own name IS the filename
 * basis. {@code contentType} and {@code sizeBytes} are the SNIFFED values
 * {@link DocumentService#captureContent} captured at upload time, never the
 * uploader's declared ones.
 *
 * Task 7's ruling requires {@code Content-Disposition: attachment} on every
 * document response, with the real filename, never inline -- but this module
 * has no controller yet (Task 22 builds it). Setting that literal HTTP
 * header is the controller's job; this record only guarantees the three
 * fields it needs to do so correctly.
 */
public record BlobContent(InputStream content, String contentType, long sizeBytes, String filename) {}
