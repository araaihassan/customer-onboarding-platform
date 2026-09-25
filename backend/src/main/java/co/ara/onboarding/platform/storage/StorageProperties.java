package co.ara.onboarding.platform.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the platform's blob storage. {@link StorageConfig} reads this
 * bean to decide which {@link BlobStore} adapter to construct -- see its own javadoc
 * for why {@code kind} has no default anywhere in this class or in {@code application.yml}.
 *
 * {@code local} and {@code s3} are nested rather than flat ({@code app.storage.local.root},
 * {@code app.storage.s3.bucket}) so each adapter's own settings are grouped under its own
 * name, the same shape {@code kind} itself picks between.
 */
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** Which adapter to construct: "local" or "s3". No default -- see StorageConfig. */
    private String kind;

    /**
     * Task 7's hardening ruling (spec §2.3/§7.6): the ceiling every upload's
     * DECLARED size is checked against, before any stream is touched. {@code Long}
     * (boxed), not a primitive, so an unset value reads as {@code null} rather than
     * silently as zero -- the same "unset is a startup failure, not an implied
     * value" shape {@code kind} already has. Ships with no default in the base
     * profile; {@code document.DocumentService}'s own {@code @PostConstruct} is
     * what actually refuses to start when this is null (not this class, the way
     * {@code kind}'s failure lives in {@link StorageConfig} rather than here) --
     * keeping the check there, rather than here, is deliberate: a blanket check in
     * this class would fire for every context that merely enables
     * {@code StorageProperties} (StorageConfigTest's own narrow contexts included),
     * even one that never touches document upload at all.
     */
    private Long maxUploadBytes;

    private final Local local = new Local();
    private final S3 s3 = new S3();

    public String getKind() { return kind; }

    public void setKind(String kind) { this.kind = kind; }

    public Long getMaxUploadBytes() { return maxUploadBytes; }

    public void setMaxUploadBytes(Long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }

    public Local getLocal() { return local; }

    public S3 getS3() { return s3; }

    /** Settings for {@link LocalFsBlobStore}, under {@code app.storage.local.*}. */
    public static class Local {

        /** Directory blobs are written under, sharded two levels deep. */
        private String root;

        public String getRoot() { return root; }

        public void setRoot(String root) { this.root = root; }
    }

    /** Settings for {@link S3BlobStore}, under {@code app.storage.s3.*}. */
    public static class S3 {

        /** The bucket blobs are written into. */
        private String bucket;

        public String getBucket() { return bucket; }

        public void setBucket(String bucket) { this.bucket = bucket; }
    }
}
