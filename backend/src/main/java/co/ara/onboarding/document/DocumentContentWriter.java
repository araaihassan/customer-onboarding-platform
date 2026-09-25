package co.ara.onboarding.document;

import co.ara.onboarding.journey.Case;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.platform.storage.BlobStore;
import co.ara.onboarding.platform.storage.StorageProperties;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Task 7 of sub-project 5's plan: the hardened capture-and-persist logic
 * extracted verbatim from {@code DocumentService} (originally Task 7 of
 * sub-project 4's own plan, spec §2.3/§7.6), so {@code agreement.AgreementFiles}
 * (Task 8) can reuse the exact same upload/version-append path instead of
 * forking a parallel copy of it -- forking is precisely the kind of drift a
 * prior security review already found and fixed once, and this class exists
 * so it cannot happen a second time.
 *
 * <p><b>This class does NO authorization of its own</b> -- no {@code
 * AuthorizedQuery} call, no {@code @RequirePermission}. Every caller (today:
 * {@link DocumentService}; from sub-project 5 Task 8 onward: also {@code
 * agreement.AgreementFiles}) has already resolved the {@link Case}/{@link
 * Document} it passes in through {@code AuthorizedQuery} and applied {@code
 * StageWriteScopeGuard} narrowing before ever calling a method here. This
 * mirrors the same "fed only a pre-authorized id/entity" shape {@code
 * journey.CaseEngine} and several {@code document} repository methods already
 * carry (see {@code AuthorizationCoverageTest.FINDER_RULE_EXCLUSIONS}'s own
 * doc comment) -- the difference here is there is no id resolution to exclude
 * in the first place, since every parameter is already-resolved domain state.
 *
 * <p>Package-private and injects two repositories ({@link DocumentRepository},
 * {@link DocumentVersionRepository}), so {@code
 * AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly}'s
 * injection-shaped rule covers this class automatically, the same sub-project
 * 3A rebind that already covers {@code journey.CaseEngine} and others. It
 * needs no exclusion entry: every repository call this class makes is {@code
 * save}/{@code saveAndFlush}, or {@link DocumentVersionRepository#maxVersionNo}
 * -- a bare aggregate {@code int}, not a scoped entity, already named safe
 * unconditionally in that test's own predicate comment -- never a {@code
 * findAll}/{@code findOne}/{@code findById}/{@code findBy*} call.
 */
@Component
class DocumentContentWriter {

    /** A magic-byte/structural sniff needs only a small prefix, never the whole file. */
    private static final int SNIFF_PREFIX_BYTES = 8192;

    private static final String DOCUMENT_VERSION_NO_UNIQUE = "document_version_no_uq";

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final BlobStore blobStore;
    private final StorageProperties storageProperties;
    private final ContentSniffGuard sniffGuard;
    private final Clock clock;

    DocumentContentWriter(DocumentRepository documents, DocumentVersionRepository versions,
                          BlobStore blobStore, StorageProperties storageProperties,
                          ContentSniffGuard sniffGuard, Clock clock) {
        this.documents = documents;
        this.versions = versions;
        this.blobStore = blobStore;
        this.storageProperties = storageProperties;
        this.sniffGuard = sniffGuard;
        this.clock = clock;
    }

    /**
     * The size ceiling, the sniffed-content MIME check and the SHA-256 digest,
     * all three from Task 7's ruling (spec §2.3/§7.6), applied to one stream
     * read exactly once:
     *
     * <ol>
     *   <li>The declared {@code sizeBytes} is checked against
     *       {@code app.storage.max-upload-bytes} before the stream is touched
     *       at all -- no I/O, no blob, no row.</li>
     *   <li>A bounded PREFIX (enough for magic-byte detection) is read into a
     *       byte array and sniffed. A rejection at this point has touched
     *       nothing else -- no blob write, no row.</li>
     *   <li>The prefix is replayed via {@link SequenceInputStream} ahead of the
     *       stream's own remainder -- not a second read from the source, a
     *       replay of the bytes already buffered followed by the rest of the
     *       SAME stream -- wrapped in a {@link DigestInputStream} before
     *       {@link BlobStore#put} ever sees it. The bytes sniffed, hashed and
     *       written are therefore provably identical: one read, start to
     *       end.</li>
     * </ol>
     */
    StoredContent capture(DocumentCategory category, InputStream content, long sizeBytes) {
        enforceSizeCeiling(sizeBytes);

        byte[] prefix = readPrefix(content, SNIFF_PREFIX_BYTES);
        String sniffedType = sniffGuard.detect(prefix);
        sniffGuard.enforce(category, sniffedType);

        MessageDigest digest = sha256();
        InputStream combined = new SequenceInputStream(new ByteArrayInputStream(prefix), content);
        DigestInputStream digestStream = new DigestInputStream(combined, digest);

        // BlobStore.put takes ownership of digestStream and closes it, reading
        // it fully -- which is what finishes updating digest with every byte.
        String storageKey = blobStore.put(digestStream, sizeBytes, sniffedType);
        String sha256Hex = HexFormat.of().formatHex(digest.digest());

        return new StoredContent(storageKey, sniffedType, sha256Hex);
    }

    /**
     * Inserts a new {@link Document} row plus its version 1, sets {@code
     * current_version_id}, and returns the saved {@link Document} -- the row-
     * writing tail moved out of {@code DocumentService.persistNewDocument}
     * verbatim. Every targeting/owner field arrives already resolved; this
     * method resolves nothing itself.
     */
    Document createDocument(Case c, UUID uploadedBy, String name, DocumentCategory category,
                            VisibilityTier tier, UUID targetDepartmentId, String targetContactLabel,
                            UUID ownerContactId, Instant expiresAt, StoredContent stored, long sizeBytes) {
        Document d = new Document();
        d.setId(Uuid7.generate());
        d.setTenantId(c.getTenantId());
        d.setCaseId(c.getId());
        d.setCustomerId(c.getCustomerId());
        d.setName(name);
        d.setCategory(category);
        d.setVisibilityTier(tier);
        d.setTargetDepartmentId(targetDepartmentId);
        d.setTargetContactLabel(targetContactLabel);
        d.setOwnerContactId(ownerContactId);
        d.setExpiresAt(expiresAt);
        d.setStatus(DocumentStatus.ACTIVE);
        d.setUploadedBy(uploadedBy);
        // Reassigned, not discarded: Document's id is assigned in Java, not
        // database-generated, so Spring Data's save() merges rather than
        // persists -- merge() returns a DIFFERENT managed instance from the
        // transient one passed in, with created_at/updated_at now populated
        // by BaseEntity's own @PrePersist. Continuing to mutate the ORIGINAL
        // (still-detached, still-null-timestamped) reference and saving it
        // again would merge those nulls straight back over the real values on
        // the second call -- exactly the "created_at violates not-null"
        // failure this reassignment avoids.
        d = documents.saveAndFlush(d);

        DocumentVersion v = new DocumentVersion(Uuid7.generate(), c.getTenantId(), d.getId(), 1,
                stored.storageKey(), sizeBytes, stored.contentType(), stored.sha256(),
                ReviewStatus.PENDING, uploadedBy, Instant.now(clock));
        versions.saveAndFlush(v);

        d.setCurrentVersionId(v.getId());
        d = documents.saveAndFlush(d);

        return d;
    }

    /**
     * The {@code maxVersionNo + 1} insert with the existing {@link
     * DocumentVersionConflictException} mapping, and the {@code
     * current_version_id} update -- moved out of {@code
     * DocumentService.addVersion} verbatim. {@code version_no} is the current
     * max plus one; two callers racing this computation can both land on the
     * same number, which {@code document_version_no_uq} then refuses as a 409
     * ({@link DocumentVersionConflictException}) rather than a silent
     * overwrite -- there is no row lock here the way {@code
     * CaseRepository.lockById} serialises {@code CaseEngine.reconcile},
     * because appending a version derives no state (spec §4.2). A new version
     * always starts {@code PENDING}, regardless of any earlier version's own
     * review outcome.
     */
    DocumentVersion appendVersion(Document d, UUID uploadedBy, StoredContent stored, long sizeBytes) {
        int nextVersionNo = versions.maxVersionNo(d.getId()) + 1;
        DocumentVersion v = new DocumentVersion(Uuid7.generate(), d.getTenantId(), d.getId(), nextVersionNo,
                stored.storageKey(), sizeBytes, stored.contentType(), stored.sha256(),
                ReviewStatus.PENDING, uploadedBy, Instant.now(clock));
        try {
            versions.saveAndFlush(v);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, DOCUMENT_VERSION_NO_UNIQUE)) {
                throw new DocumentVersionConflictException(d.getId(), e);
            }
            // Every other constraint is rethrown untouched -- reporting an
            // unrelated violation as a version race would send the caller
            // hunting for a conflict that does not exist.
            throw e;
        }

        d.setCurrentVersionId(v.getId());
        documents.saveAndFlush(d);

        return v;
    }

    private void enforceSizeCeiling(long sizeBytes) {
        long max = storageProperties.getMaxUploadBytes();
        if (sizeBytes > max) throw new UploadTooLargeException(sizeBytes, max);
    }

    private static byte[] readPrefix(InputStream in, int max) {
        try {
            byte[] buf = new byte[max];
            int total = 0;
            int r;
            while (total < max && (r = in.read(buf, total, max - total)) != -1) {
                total += r;
            }
            return total == max ? buf : Arrays.copyOf(buf, total);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read upload prefix", e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Matched on the constraint name Hibernate reports, not on message text,
     * which is Postgres's to reword -- the same idiom
     * {@code programme.ProgrammeMembershipService.violates} and
     * {@code customer.CustomerContactService.violates} both already use.
     */
    private static boolean violates(Throwable failure, String constraintName) {
        for (Throwable t = failure; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && constraintName.equals(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    record StoredContent(String storageKey, String contentType, String sha256) {}
}
