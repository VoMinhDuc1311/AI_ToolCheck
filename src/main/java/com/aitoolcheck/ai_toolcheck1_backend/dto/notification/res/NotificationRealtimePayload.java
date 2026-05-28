package com.aitoolcheck.ai_toolcheck1_backend.dto.notification.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationSeverity;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight payload sent via WebSocket to the client on the
 * user-specific destination: {@code /user/queue/notifications}.
 *
 * <p>Must NOT include JWT tokens, API keys, source code, AI prompts, or stack traces.
 */
@Getter
@Builder
public class NotificationRealtimePayload {

    private UUID notificationId;
    private UUID projectId;
    private NotificationType type;
    private NotificationSeverity severity;
    private String title;
    private String message;
    private String actionUrl;
    private LocalDateTime createdAt;
}
