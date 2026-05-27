package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.notification.res.NotificationRealtimePayload;

import java.util.UUID;

/**
 * Service for delivering real-time notification payloads to connected WebSocket clients.
 *
 * <p>Implementation uses {@code SimpMessagingTemplate.convertAndSendToUser()} to send
 * only to the specific authenticated user's session on {@code /user/queue/notifications}.
 *
 * <p>This is a best-effort delivery mechanism — if the user is offline,
 * they will see the notification from DB when they next load the app.
 */
public interface NotificationRealtimePublisher {

    /**
     * Attempt to deliver a realtime notification payload to a specific user.
     *
     * @param recipientUsername the Spring Security principal name used by STOMP session
     * @param payload           the safe notification payload to send
     */
    void sendToUser(String recipientUsername, NotificationRealtimePayload payload);
}
