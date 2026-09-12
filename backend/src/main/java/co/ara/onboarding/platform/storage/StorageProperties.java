package co.ara.onboarding.platform.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for {@link LocalFsBlobStore}. A future S3-backed adapter (Task
 * 5) will carry its own properties class rather than growing this one -- the
 * two adapters share a contract, not a configuration surface.
 */
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** Directory blobs are written under, sharded two levels deep. */
    private String localRoot;

    public String getLocalRoot() { return localRoot; }

    public void setLocalRoot(String localRoot) { this.localRoot = localRoot; }
}
