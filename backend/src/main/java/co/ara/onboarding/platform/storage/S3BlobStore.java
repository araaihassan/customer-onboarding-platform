package co.ara.onboarding.platform.storage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * An S3-compatible {@link BlobStore}, exercised in {@code S3BlobStoreTest}
 * against a real MinIO container rather than a mocked {@code S3Client} --
 * MinIO speaks the same API surface this class uses, so the same adapter
 * covers AWS S3, MinIO, R2 and any other S3-compatible backend. See
 * {@link LocalFsBlobStore} for the filesystem-backed sibling; both satisfy
 * {@link BlobStoreContract}.
 */
public class S3BlobStore implements BlobStore {

    private final S3Client client;
    private final String bucket;

    public S3BlobStore(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public String put(InputStream content, long sizeBytes, String contentType) {
        String key = StorageKeys.newKey();
        // put() takes ownership of content (BlobStore's own contract) and closes
        // it here, success or failure -- try-with-resources runs the close in a
        // finally regardless of whether putObject throws.
        try (content) {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromInputStream(content, sizeBytes));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not close blob content stream for key " + key, e);
        }
        return key;
    }

    @Override
    public InputStream open(String storageKey) {
        try {
            return client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .build());
        } catch (NoSuchKeyException e) {
            throw new BlobNotFoundException(storageKey, e);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            // HeadObject carries no response body, so some S3-compatible
            // backends surface a missing key as a bare 404 status rather than
            // the NoSuchKeyException the SDK derives from GetObject's body.
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }
}
