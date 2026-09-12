package co.ara.onboarding.platform.storage;

import co.ara.onboarding.platform.Uuid7;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A filesystem-backed {@link BlobStore}, for local development and any
 * deployment that does not need object storage. Task 5 adds an S3-backed
 * sibling; both satisfy {@link BlobStoreContract}.
 */
@Component
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

    @Autowired
    public LocalFsBlobStore(StorageProperties properties) {
        this(Path.of(properties.getLocalRoot()));
    }

    @Override
    public String put(InputStream content, long sizeBytes, String contentType) {
        String key = newKey();
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
            try {
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

    /**
     * A UUIDv7 rendered base32-lowercase without padding, sharded two levels. UUIDv7
     * rather than SecureRandom because a storage key need only be unique, not
     * unpredictable -- access is always mediated by the application (spec 7.3), never
     * by key secrecy. CLAUDE.md's rule is that values needing unpredictability use
     * SecureRandom; this is explicitly not one of them, and saying so here stops a
     * future reader "fixing" it.
     */
    private String newKey() {
        String flat = Uuid7.generate().toString().replace("-", "");
        return flat.substring(0, 2) + "/" + flat.substring(2, 4) + "/" + flat;
    }
}
