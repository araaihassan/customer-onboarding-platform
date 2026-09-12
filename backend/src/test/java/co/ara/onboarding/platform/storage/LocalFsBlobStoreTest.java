package co.ara.onboarding.platform.storage;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class LocalFsBlobStoreTest extends BlobStoreContract {

    @TempDir static Path root;

    private final BlobStore store = new LocalFsBlobStore(root);

    @Override protected BlobStore store() { return store; }
}
