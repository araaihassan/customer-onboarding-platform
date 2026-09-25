package co.ara.onboarding.platform.storage;

import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;

/**
 * The S3 adapter is exercised against a REAL object store, not a mock. A mocked
 * S3Client would assert that this class calls the SDK the way the author
 * imagined, which is the one thing that is never in doubt. MinIO is
 * S3-compatible, so this same adapter covers AWS S3, MinIO, R2 and friends.
 */
@Testcontainers
class S3BlobStoreTest extends BlobStoreContract {

    // The brief's image reference, "minio/minio:...", 404s: MinIO retired that Docker Hub
    // repository (pull access denied, "repository does not exist") and now publishes
    // exclusively to quay.io. Confirmed by hand with `docker pull` against both registries
    // before changing this. Same tag, same image, different registry -- so it is declared
    // an explicit compatible substitute rather than tripping MinIOContainer's own
    // same-repository sanity check.
    @Container
    static final MinIOContainer MINIO = new MinIOContainer(
            DockerImageName.parse("quay.io/minio/minio:RELEASE.2024-11-07T00-52-20Z")
                    .asCompatibleSubstituteFor("minio/minio"));

    private static S3BlobStore store;

    @BeforeAll
    static void setUp() {
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)   // MinIO does not do virtual-host addressing
                .build();
        client.createBucket(CreateBucketRequest.builder().bucket("documents").build());
        store = new S3BlobStore(client, "documents");
    }

    @Override protected BlobStore store() { return store; }
}
