package co.ara.onboarding.platform.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A filesystem-backed {@link BlobStore}, for local development and any
 * deployment that does not need object storage. Both this and its S3-backed
 * sibling satisfy {@link BlobStoreContract}.
 *
 * Deliberately NOT a Spring bean ({@code @Component}) -- {@link StorageConfig}
 * constructs whichever adapter {@code app.storage.kind} selects explicitly, so
 * that exactly one {@link BlobStore} candidate ever exists in the context.
 * Leaving this annotated alongside a candidate {@code S3BlobStore} bean would
 * give Spring two unqualified {@code BlobStore} implementations with no
 * disambiguation -- exactly the ambiguity {@code StorageConfig} exists to
 * prevent.
 */
public class LocalFsBlobStore implements BlobStore {

    private final Path root;

    public LocalFsBlobStore(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create storage root " + root, e);
        }
    }

    @Override
    public String put(InputStream content, long sizeBytes, String contentType) {
        String key = StorageKeys.newKey();
        Path target = root.resolve(key);
        Path shardDir = target.getParent();

        try {
            Files.createDirectories(shardDir);
            // Write to a temp file in the SAME directory as the final target, then
            // move atomically into place. A same-directory temp file guarantees the
            // move is a rename on the same filesystem rather than a copy, so a crash
            // mid-upload never leaves a partially-written blob readable under its
            // final key -- readers only ever see the temp name (which nothing looks
            // up) or the fully-written final file, never a half-written one.
            Path tempFile = Files.createTempFile(shardDir, "upload-", ".tmp");
            try (content) {
                // put() takes ownership of content (BlobStore's own contract) and
                // closes it here, success or failure -- Files.copy reads it fully but
                // never closes it itself, and leaving that to the caller is exactly
                // how a real multipart upload stream (Task 15) would leak a file
                // descriptor per put().
                Files.copy(content, tempFile, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write blob for key " + key, e);
        }

        return key;
    }

    @Override
    public InputStream open(String storageKey) {
        Path path = root.resolve(storageKey);
        if (!Files.isRegularFile(path)) {
            throw new BlobNotFoundException(storageKey);
        }
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new BlobNotFoundException(storageKey, e);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        return Files.isRegularFile(root.resolve(storageKey));
    }

}
