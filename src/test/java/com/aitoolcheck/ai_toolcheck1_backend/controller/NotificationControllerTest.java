package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.notification.res.NotificationPageResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.notification.res.NotificationResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationControllerTest {

    @Test
    void listDelegatesToCurrentUserScopedService() {
        NotificationService service = mock(NotificationService.class);
        when(service.getMyNotifications(any(Pageable.class))).thenReturn(NotificationPageResponse.builder()
                .content(List.of())
                .page(0)
                .size(20)
                .totalElements(0)
                .totalPages(0)
                .last(true)
                .build());

        NotificationController controller = new NotificationController(service);

        assertEquals("SUCCESS", controller.list(0, 20).getBody().getCode());
        verify(service).getMyNotifications(any(Pageable.class));
    }

    @Test
    void unreadCountDelegatesToCurrentUserScopedService() {
        NotificationService service = mock(NotificationService.class);
        when(service.getMyUnreadCount()).thenReturn(4L);
        NotificationController controller = new NotificationController(service);

        @SuppressWarnings("unchecked")
        Map<String, Long> data = (Map<String, Long>) controller.unreadCount().getBody().getData();

        assertEquals(4L, data.get("unreadCount"));
    }

    @Test
    void markAsReadDelegatesToCurrentUserScopedService() {
        NotificationService service = mock(NotificationService.class);
        UUID id = UUID.randomUUID();
        when(service.markAsRead(id)).thenReturn(NotificationResponse.builder().id(id).build());
        NotificationController controller = new NotificationController(service);

        assertEquals(id, controller.markAsRead(id).getBody().getData().getId());
        verify(service).markAsRead(id);
    }
}
