package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.notification.res.NotificationPageResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.notification.res.NotificationResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationSeverity;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.model.Notification;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.NotificationRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.CurrentUserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional
    public UUID createNotification(
            AppUser recipient,
            SourceProject project,
            NotificationType type,
            NotificationSeverity severity,
            String title,
            String message,
            String actionUrl,
            String metadataJson) {

        Notification notification = Notification.builder()
                .recipientUser(recipient)
                .project(project)
                .type(type)
                .severity(severity)
                .title(title)
                .message(message)
                .actionUrl(actionUrl)
                .metadataJson(sanitizeMetadataJson(metadataJson))
                .readFlag(false)
                .build();

        Notification saved = notificationRepository.save(notification);
        log.debug("[NotificationService] Created notification id={} for userId={} type={}",
                saved.getId(), recipient.getId(), type);
        return saved.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationPageResponse getMyNotifications(Pageable pageable) {
        AppUser currentUser = currentUserService.getCurrentUser();
        Page<Notification> page = notificationRepository
                .findByRecipientUser_IdOrderByCreatedAtDesc(currentUser.getId(), pageable);

        List<NotificationResponse> content = page.getContent().stream()
                .map(this::toResponse)
                .toList();

        return NotificationPageResponse.builder()
                .content(content)
                .page(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public long getMyUnreadCount() {
        AppUser currentUser = currentUserService.getCurrentUser();
        return notificationRepository.countByRecipientUser_IdAndReadFlagFalse(currentUser.getId());
    }

    @Override
    @Transactional
    public NotificationResponse markAsRead(UUID notificationId) {
        AppUser currentUser = currentUserService.getCurrentUser();

        Notification notification = notificationRepository
                .findByIdAndRecipientUser_Id(notificationId, currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));

        if (Boolean.TRUE.equals(notification.getReadFlag())) {
            return toResponse(notification); // Already read — idempotent
        }

        LocalDateTime now = LocalDateTime.now();
        notification.setReadFlag(true);
        notification.setReadAt(now);
        Notification saved = notificationRepository.save(notification);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public int markAllAsRead() {
        AppUser currentUser = currentUserService.getCurrentUser();
        return notificationRepository.markAllReadForUser(currentUser.getId(), LocalDateTime.now());
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private NotificationResponse toResponse(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .projectId(n.getProject() == null ? null : n.getProject().getId())
                .type(n.getType())
                .severity(n.getSeverity())
                .title(n.getTitle())
                .message(n.getMessage())
                .actionUrl(n.getActionUrl())
                .readFlag(n.getReadFlag())
                .readAt(n.getReadAt())
                .createdAt(n.getCreatedAt())
                .build();
    }

    private String sanitizeMetadataJson(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }

        String lower = metadataJson.toLowerCase(Locale.ROOT);
        List<String> forbiddenMarkers = List.of(
                "authorization",
                "accesstoken",
                "refresh_token",
                "refreshtoken",
                "jwt",
                "apikey",
                "api_key",
                "password",
                "secret",
                "sourcecontent",
                "source code",
                "prompt",
                "stacktrace",
                "exception"
        );

        for (String marker : forbiddenMarkers) {
            if (lower.contains(marker)) {
                log.warn("[NotificationService] Dropping unsafe notification metadata containing marker={}", marker);
                return null;
            }
        }

        return metadataJson.length() <= 2000 ? metadataJson : metadataJson.substring(0, 2000);
    }
}
