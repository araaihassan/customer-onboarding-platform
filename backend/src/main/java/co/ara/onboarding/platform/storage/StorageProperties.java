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

    private final Local local = new Local();
    private final S3 s3 = new S3();

    public String getKind() { return kind; }

    public void setKind(String kind) { this.kind = kind; }

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
