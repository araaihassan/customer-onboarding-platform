package co.ara.onboarding.document;

import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.platform.storage.BlobStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Task 16: streaming download -- the counterpart to Task 15's upload, and the
 * piece of Task 7's upload-hardening ruling (spec 2.3/7.6) that requires
 * every document response to carry {@code Content-Disposition: attachment},
 * never inline. This class supplies the content half of that guarantee -- a
 * {@link BlobContent} carrying the real filename and the sniffed content
 * type -- while the literal HTTP header is set by Task 22's controller, the
 * only place an HTTP response actually exists in this module today.
 *
 * NEVER a presigned URL: the local filesystem {@link BlobStore} adapter
 * cannot presign at all, and a presigned URL against the S3 adapter would
 * outlive a share revoked a moment later. Every byte is streamed through the
 * application instead, the same as {@link DocumentService#upload} always
 * writes one through it.
 *
 * {@link Document} is resolved through {@link AuthorizedQuery} FIRST, gated
 * {@code document.view} -- the same permission
 * {@code scoping.DocumentAudienceFilter} narrows on (spec 6.3/6.4), so a
 * document targeted at a department this actor is not in is refused here
 * exactly as it already is for a plain metadata read, even at ALL scope
 * ({@code security.DocumentAudienceTest}'s whole point).
 *
 * {@link DocumentVersion} has its own {@code ResourceAuthorizationDescriptor}
 * (Task 10) but NO {@link co.ara.onboarding.authz.AudienceFilter} of its
 * own -- only {@link Document} does. Resolving a version directly through
 * {@code AuthorizedQuery} keyed on {@code DocumentVersion.class} would
 * therefore bypass the entire audience mechanism this module's security
 * model depends on. The specific version is looked up only AFTER the parent
 * document has already been authorized, through
 * {@link DocumentVersionRepository#versionAt} -- never a second
 * {@code AuthorizedQuery} call against {@code DocumentVersion}.
 */
@Service
public class DocumentContentService {

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final AuthorizedQuery authorizedQuery;
    private final BlobStore blobStore;

    public DocumentContentService(DocumentRepository documents, DocumentVersionRepository versions,
                                   AuthorizedQuery authorizedQuery, BlobStore blobStore) {
        this.documents = documents;
        this.versions = versions;
        this.authorizedQuery = authorizedQuery;
        this.blobStore = blobStore;
    }

    @RequirePermission(PermissionKeys.DOCUMENT_VIEW)
    @Transactional(readOnly = true)
    public BlobContent open(UUID documentId, int versionNo) {
        Document d = authorizedQuery.getById(documents, Document.class, PermissionKeys.DOCUMENT_VIEW, documentId);
        DocumentVersion v = versions.versionAt(d.getId(), versionNo)
                .orElseThrow(() -> new NoSuchElementException("Not found"));
        InputStream stream = blobStore.open(v.getStorageKey());
        return new BlobContent(stream, v.getContentType(), v.getSizeBytes(), d.getName());
    }
}
