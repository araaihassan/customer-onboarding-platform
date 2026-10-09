package co.ara.onboarding.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {

    /** The inbox's "mark all read" (6B spec 7.3): only the recipient's own unread in-app rows. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.readAt = :at, n.updatedAt = :at "
            + "where n.recipientUserId = :recipient and n.inApp = true and n.readAt is null")
    int markAllRead(@Param("recipient") UUID recipient, @Param("at") Instant at);
}
