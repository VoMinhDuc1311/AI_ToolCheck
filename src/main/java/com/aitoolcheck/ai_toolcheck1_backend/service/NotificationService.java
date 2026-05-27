package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.notification.res.NotificationPageResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.notification.res.NotificationResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationSeverity;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationType;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Service for persisting and querying user notifications.
 *
 * <p>Authorization rules:
 * <ul>
 *   <li>Users can only see and modify their own notifications.</li>
 *   <li>No cross-user access is possible through these methods.</li>
 * </ul>
 */
public interface NotificationService {

    /**
     * Create and persist a notification for a recipient user.
     * Returns the ID of the created notification.
     */
    UUID createNotification(
            AppUser recipient,
            SourceProject project,
            NotificationType type,
            NotificationSeverity severity,
            String title,
            String message,
            String actionUrl,
            String metadataJson
    );

    /**
     * Get paginated notifications for the current authenticated user.
     */
    NotificationPageResponse getMyNotifications(Pageable pageable);

    /**
     * Count unread notifications for the current authenticated user.
     */
    long getMyUnreadCount();

    /**
     * Mark a single notification as read. Verifies ownership — throws ForbiddenException
     * if the notification does not belong to the current user.
     */
    NotificationResponse markAsRead(UUID notificationId);

    /**
     * Mark ALL unread notifications as read for the current authenticated user.
     * Returns the number of notifications updated.
     */
    int markAllAsRead();
}
