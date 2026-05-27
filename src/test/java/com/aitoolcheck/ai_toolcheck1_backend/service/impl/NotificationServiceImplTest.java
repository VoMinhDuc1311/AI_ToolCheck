package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationSeverity;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.model.Notification;
import com.aitoolcheck.ai_toolcheck1_backend.repository.NotificationRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private CurrentUserService currentUserService;

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(notificationRepository, currentUserService);
    }

    @Test
    void creatingNotificationPersistsRecord() {
        AppUser recipient = user(UUID.randomUUID(), "user@example.com");
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            notification.setId(UUID.randomUUID());
            return notification;
        });

        UUID id = service.createNotification(
                recipient,
                null,
                NotificationType.INFO,
                NotificationSeverity.INFO,
                "Title",
                "Message",
                null,
                "{\"projectId\":\"p\"}");

        verify(notificationRepository).save(any(Notification.class));
        org.junit.jupiter.api.Assertions.assertNotNull(id);
    }

    @Test
    void currentUserOnlyListsOwnNotifications() {
        UUID userId = UUID.randomUUID();
        when(currentUserService.getCurrentUser()).thenReturn(user(userId, "me@example.com"));
        when(notificationRepository.findByRecipientUser_IdOrderByCreatedAtDesc(userId, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of()));

        service.getMyNotifications(PageRequest.of(0, 20));

        verify(notificationRepository).findByRecipientUser_IdOrderByCreatedAtDesc(userId, PageRequest.of(0, 20));
    }

    @Test
    void unreadCountOnlyCountsCurrentUser() {
        UUID userId = UUID.randomUUID();
        when(currentUserService.getCurrentUser()).thenReturn(user(userId, "me@example.com"));
        when(notificationRepository.countByRecipientUser_IdAndReadFlagFalse(userId)).thenReturn(3L);

        assertEquals(3L, service.getMyUnreadCount());
    }

    @Test
    void userCannotMarkAnotherUsersNotificationAsRead() {
        UUID userId = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        when(currentUserService.getCurrentUser()).thenReturn(user(userId, "me@example.com"));
        when(notificationRepository.findByIdAndRecipientUser_Id(notificationId, userId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.markAsRead(notificationId));
    }

    @Test
    void markAllReadAffectsOnlyCurrentUser() {
        UUID userId = UUID.randomUUID();
        when(currentUserService.getCurrentUser()).thenReturn(user(userId, "me@example.com"));
        when(notificationRepository.markAllReadForUser(any(), any())).thenReturn(2);

        assertEquals(2, service.markAllAsRead());
        verify(notificationRepository).markAllReadForUser(org.mockito.ArgumentMatchers.eq(userId), any());
    }

    @Test
    void unsafeMetadataIsDropped() {
        AppUser recipient = user(UUID.randomUUID(), "user@example.com");
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            assertNull(notification.getMetadataJson());
            notification.setId(UUID.randomUUID());
            return notification;
        });

        service.createNotification(
                recipient,
                null,
                NotificationType.INFO,
                NotificationSeverity.INFO,
                "Title",
                "Message",
                null,
                "{\"accessToken\":\"secret\"}");
    }

    private AppUser user(UUID id, String email) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setEmail(email);
        return user;
    }
}
