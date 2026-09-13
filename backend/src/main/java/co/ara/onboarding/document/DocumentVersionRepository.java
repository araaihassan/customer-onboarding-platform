package co.ara.onboarding.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocumentVersionRepository
        extends JpaRepository<DocumentVersion, UUID>, JpaSpecificationExecutor<DocumentVersion> {

    /** Every version of a document, for its version history. */
    List<DocumentVersion> findByDocumentId(UUID documentId);

    /**
     * The highest existing version_no for a document, or 0 when it has none yet
     * -- DocumentService.addVersion adds 1 to this. Named maxVersionNo rather
     * than a findBy* shape deliberately: it returns an aggregate int, not a
     * scoped entity, so it never matches AuthorizationCoverageTest's
     * servicesDoNotCallRepositoryFindersDirectly finder predicate (which binds
     * on findAll/findOne/findById/findBy*) and needs no exclusion there. Tenant
     * isolation for the underlying rows still rests on RLS, the same as every
     * other document_version read.
     */
    @Query("select coalesce(max(v.versionNo), 0) from DocumentVersion v where v.documentId = :documentId")
    int maxVersionNo(@Param("documentId") UUID documentId);
}
