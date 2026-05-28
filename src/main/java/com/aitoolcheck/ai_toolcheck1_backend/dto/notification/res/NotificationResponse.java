package com.aitoolcheck.ai_toolcheck1_backend.dto.notification.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationSeverity;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO returned to the client for a single notification.
 * Does not expose internal entity references or sensitive metadata.
 */
@Getter
@Builder
public class NotificationResponse {

    private UUID id;
    private UUID projectId;
    private NotificationType type;
    private NotificationSeverity severity;
    private String title;
    private String message;
    private String actionUrl;
    private Boolean readFlag;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
