package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.notification.res.NotificationRealtimePayload;
import com.aitoolcheck.ai_toolcheck1_backend.service.NotificationRealtimePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Delivers real-time notification payloads to individual users via STOMP WebSocket.
 *
 * <p>Uses {@code SimpMessagingTemplate.convertAndSendToUser()} which routes to
 * the user-specific destination: {@code /user/queue/notifications}.
 *
 * <p>The {@code username} parameter must match the Spring Security principal name
 * set by {@link com.aitoolcheck.ai_toolcheck1_backend.config.WebSocketAuthChannelInterceptor},
 * which is the value of {@code CustomUserDetails.getUsername()} (the user's email).
 *
 * <p>Delivery is best-effort: if the user is offline, this silently no-ops.
 * Persistent DB notifications ensure offline users are not missed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationRealtimePublisherImpl implements NotificationRealtimePublisher {

    private static final String USER_NOTIFICATION_DESTINATION = "/queue/notifications";

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void sendToUser(String recipientUsername, NotificationRealtimePayload payload) {
        if (recipientUsername == null || recipientUsername.isBlank()) {
            log.warn("[NotificationRealtimePublisher] Skipping: recipientUsername is blank");
            return;
        }
        if (payload == null) {
            log.warn("[NotificationRealtimePublisher] Skipping: payload is null for user={}", recipientUsername);
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(recipientUsername, USER_NOTIFICATION_DESTINATION, payload);
            log.debug("[NotificationRealtimePublisher] Sent {} notification to user={}",
                    payload.getType(), recipientUsername);
        } catch (Exception ex) {
            // Best-effort: log and continue. Offline users will see notifications from DB.
            log.warn("[NotificationRealtimePublisher] Failed to send realtime notification to user={}: {}",
                    recipientUsername, ex.getMessage());
        }
    }
}
