package co.ara.onboarding.platform.storage;

import jakarta.servlet.MultipartConfigElement;
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

    /**
     * Task 22 review Finding 1: nothing configured {@code
     * spring.servlet.multipart.max-file-size}/{@code max-request-size}, so Spring
     * Boot's own UNCONFIGURED defaults (1 MiB per file, 10 MiB per request) sat in
     * front of {@code document.DocumentService}'s own {@code
     * app.storage.max-upload-bytes} ceiling -- an oversized upload was refused by
     * the servlet container, as an unmapped {@code MaxUploadSizeExceededException}
     * (a raw 500), long before {@code DocumentService}'s own checked ceiling and
     * its mapped 413 ever had a chance to run.
     *
     * <p>Defining this bean ourselves is what actually wins: {@code
     * MultipartAutoConfiguration.multipartConfigElement()} is {@code
     * @ConditionalOnMissingBean}, so registering one here suppresses Boot's own
     * default and every {@code Servlet} bean picks this one up instead (wired by
     * {@code ServletWebServerApplicationContext} the same way either bean would
     * be). Binding it straight to {@code app.storage.max-upload-bytes} -- the
     * SAME property {@link DocumentService} already enforces at the application
     * layer -- keeps the two ceilings from drifting apart: one property to
     * change, not two, and a deployment that raises the application's own limit
     * does not silently reintroduce this exact bug at a lower, forgotten one.
     *
     * <p>{@code maxRequestSize} adds a fixed slack on top of {@code
     * maxFileSize} for the surrounding multipart envelope itself (boundaries,
     * part headers, and -- on the document create endpoint only -- the small
     * "metadata" JSON part), so a file sized exactly at the ceiling does not tip
     * the overall request over a second, tighter limit purely from that
     * overhead.
     *
     * <p>Falls back to 25 MiB (the same value {@code application.yml}'s own
     * dev/test profile document defaults {@code STORAGE_MAX_UPLOAD_BYTES} to)
     * when {@code app.storage.max-upload-bytes} is unset, rather than throwing
     * here: {@link DocumentService}'s own {@code @PostConstruct} is the one place
     * that refuses to start the application over an unset ceiling (see its own
     * javadoc for why it lives there and not on {@link StorageProperties}
     * itself), and duplicating that guard here would just produce a second,
     * less legible failure for the identical condition -- this bean only needs
     * SOME positive number to hand the servlet container regardless of profile,
     * not the authoritative refusal.
     */
    private static final long DEFAULT_MAX_UPLOAD_BYTES = 26_214_400; // 25 MiB
    private static final long MULTIPART_ENVELOPE_SLACK_BYTES = 65_536; // 64 KiB

    @Bean
    public MultipartConfigElement multipartConfigElement(StorageProperties properties) {
        long maxFileBytes = properties.getMaxUploadBytes() != null
                ? properties.getMaxUploadBytes()
                : DEFAULT_MAX_UPLOAD_BYTES;
        long maxRequestBytes = maxFileBytes + MULTIPART_ENVELOPE_SLACK_BYTES;
        return new MultipartConfigElement("", maxFileBytes, maxRequestBytes, 0);
    }

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
