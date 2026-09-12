package co.ara.onboarding.platform.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Selects exactly one {@link BlobStore} bean, by {@code app.storage.kind}. There is
 * NO default -- an unset or unrecognised value refuses to start the application,
 * rather than falling back to {@code local}.
 *
 * This is the same shape {@code JWT_SECRET} and {@code DB_APP_PASSWORD} are guarded
 * in ({@code JwtProperties}, {@code DatabaseCredentialsGuard}): configuration that
 * silently falls back to a default is configuration the deployment that forgot it
 * will use. Here an {@code s3} deployment that silently fell back to the local
 * filesystem would write documents to a container's ephemeral disk and lose them on
 * restart, with no error anywhere -- so every failure names the specific property
 * that is wrong, the same discipline those two guards hold to.
 *
 * {@link LocalFsBlobStore} and {@link S3BlobStore} are deliberately not Spring beans
 * themselves; this class constructs whichever one is selected explicitly, so exactly
 * one {@link BlobStore} candidate ever exists in the context.
 */
@Configuration(proxyBeanMethods = false)
public class StorageConfig {

    @Bean
    public BlobStore blobStore(StorageProperties properties) {
        String kind = properties.getKind();
        if (kind == null || kind.isBlank()) {
            throw new IllegalStateException(
                    "app.storage.kind is not set. Set it to \"local\" or \"s3\" before this"
                            + " application can start -- there is no implied default, because an"
                            + " s3 deployment that silently fell back to the local filesystem would"
                            + " lose every document written to a container's ephemeral disk on"
                            + " restart, with no error anywhere.");
        }

        return switch (kind.trim().toLowerCase(Locale.ROOT)) {
            case "local" -> buildLocal(properties);
            case "s3" -> buildS3(properties);
            default -> throw new IllegalStateException(
                    "app.storage.kind is set to \"" + kind + "\", which is not a recognised"
                            + " storage adapter. Set it to \"local\" or \"s3\".");
        };
    }

    private BlobStore buildLocal(StorageProperties properties) {
        String root = properties.getLocal().getRoot();
        if (root == null || root.isBlank()) {
            throw new IllegalStateException(
                    "app.storage.local.root is not set. app.storage.kind is \"local\", which"
                            + " writes blobs to the filesystem and needs a directory to write them"
                            + " under -- set app.storage.local.root (for example: ./data/blobs).");
        }
        return new LocalFsBlobStore(Path.of(root));
    }

    private BlobStore buildS3(StorageProperties properties) {
        String bucket = properties.getS3().getBucket();
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException(
                    "app.storage.s3.bucket is not set. app.storage.kind is \"s3\", which needs a"
                            + " bucket to write blobs into -- set app.storage.s3.bucket.");
        }
        // Region and credentials resolve through the SDK's default chain (environment
        // variables, ~/.aws/config, an instance/task role) -- the same convention every
        // other AWS-facing deployment of this shape relies on, and deliberately not
        // re-plumbed through app.storage.* here: this task's own scope is adapter
        // selection, not a second configuration surface for the SDK's own discovery.
        S3Client client = S3Client.builder().build();
        return new S3BlobStore(client, bucket);
    }
}
