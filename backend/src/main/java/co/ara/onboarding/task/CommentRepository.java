package co.ara.onboarding.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface CommentRepository
        extends JpaRepository<Comment, UUID>, JpaSpecificationExecutor<Comment> {

    /** Matches comment_tenant_resource_idx's lead columns after tenant_id. */
    List<Comment> findByResourceTypeAndResourceIdOrderByCreatedAt(
            CommentResourceType resourceType, UUID resourceId);
}
