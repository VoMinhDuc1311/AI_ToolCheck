package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Paginated list of notifications for a specific user, ordered by recency.
     */
    Page<Notification> findByRecipientUser_IdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /**
     * Count of unread notifications for a specific user.
     */
    long countByRecipientUser_IdAndReadFlagFalse(UUID userId);

    /**
     * Find a specific notification, scoped to a user (prevents cross-user access).
     */
    Optional<Notification> findByIdAndRecipientUser_Id(UUID notificationId, UUID userId);

    /**
     * Bulk-mark all unread notifications for a user as read.
     */
    @Modifying
    @Query("UPDATE Notification n SET n.readFlag = true, n.readAt = :readAt, n.updatedAt = :readAt " +
           "WHERE n.recipientUser.id = :userId AND n.readFlag = false")
    int markAllReadForUser(@Param("userId") UUID userId, @Param("readAt") LocalDateTime readAt);
}
