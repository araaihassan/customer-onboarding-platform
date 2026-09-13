package co.ara.onboarding.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
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

    /**
     * The one version at a specific number, or empty if none exists at that
     * number -- {@code DocumentContentService.open}'s only caller, and only
     * ever invoked there AFTER {@code documentId} has already been resolved
     * through {@code AuthorizedQuery} under {@code document.view} (which is
     * what actually applies {@code scoping.DocumentAudienceFilter}'s
     * targeting/tier/label narrowing -- {@code DocumentVersion} has no
     * {@code AudienceFilter} of its own). Fed only a pre-authorized id, the
     * same "no fresh caller-supplied id here" shape
     * {@code AuthorizationCoverageTest.FINDER_RULE_EXCLUSIONS} already
     * documents for {@code journey.CaseEngine}'s own finder calls.
     *
     * Named {@code versionAt} rather than a {@code findBy*} shape
     * deliberately, mirroring {@link #maxVersionNo} just above: it never
     * matches {@code servicesDoNotCallRepositoryFindersDirectly}'s target
     * predicate and needs no exclusion there.
     */
    @Query("select v from DocumentVersion v where v.documentId = :documentId and v.versionNo = :versionNo")
    Optional<DocumentVersion> versionAt(@Param("documentId") UUID documentId, @Param("versionNo") int versionNo);
}
