package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.notification.res.NotificationRealtimePayload;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationRealtimePublisherImplTest {

    @Test
    void sendsToUserNotificationQueue() {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        NotificationRealtimePublisherImpl publisher = new NotificationRealtimePublisherImpl(messagingTemplate);
        NotificationRealtimePayload payload = NotificationRealtimePayload.builder()
                .title("Title")
                .message("Message")
                .build();

        publisher.sendToUser("user@example.com", payload);

        verify(messagingTemplate).convertAndSendToUser("user@example.com", "/queue/notifications", payload);
    }
}
