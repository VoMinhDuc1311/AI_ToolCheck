package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.notification.res.NotificationPageResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.notification.res.NotificationResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "User notification APIs")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @Operation(
            summary = "List my notifications",
            description = "Get paginated notifications for the current authenticated user, ordered by most recent.",
            operationId = "listMyNotifications"
    )
    public ResponseEntity<ApiResponse<NotificationPageResponse>> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {

        // Clamp page size to prevent abuse
        int clampedSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampedSize);
        NotificationPageResponse response = notificationService.getMyNotifications(pageable);
        return ResponseEntity.ok(success("Notifications fetched successfully.", response));
    }

    @GetMapping("/unread-count")
    @Operation(
            summary = "Get unread notification count",
            description = "Returns the count of unread notifications for the current authenticated user.",
            operationId = "getUnreadNotificationCount"
    )
    public ResponseEntity<ApiResponse<Map<String, Long>>> unreadCount() {
        long count = notificationService.getMyUnreadCount();
        return ResponseEntity.ok(success("Unread count fetched successfully.", Map.of("unreadCount", count)));
    }

    @PatchMapping("/{notificationId}/read")
    @Operation(
            summary = "Mark notification as read",
            description = "Mark a single notification as read. User can only mark their own notifications.",
            operationId = "markNotificationAsRead"
    )
    public ResponseEntity<ApiResponse<NotificationResponse>> markAsRead(
            @PathVariable UUID notificationId) {
        NotificationResponse response = notificationService.markAsRead(notificationId);
        return ResponseEntity.ok(success("Notification marked as read.", response));
    }

    @PatchMapping("/read-all")
    @Operation(
            summary = "Mark all notifications as read",
            description = "Mark all unread notifications as read for the current authenticated user.",
            operationId = "markAllNotificationsAsRead"
    )
    public ResponseEntity<ApiResponse<Map<String, Integer>>> markAllAsRead() {
        int updated = notificationService.markAllAsRead();
        return ResponseEntity.ok(success(
                "All notifications marked as read.",
                Map.of("updatedCount", updated)));
    }

    private <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .code("SUCCESS")
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
