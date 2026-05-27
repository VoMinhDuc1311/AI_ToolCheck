package com.aitoolcheck.ai_toolcheck1_backend.service.notification;

import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationSeverity;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationType;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * Domain event published by business services after completing a significant
 * project activity. The {@link NotificationEventListener} handles this event
 * AFTER transaction commit to persist and optionally deliver realtime notification.
 *
 * <p>Content rules:
 * <ul>
 *   <li>title and message must be user-friendly; no stack traces, no AI prompts.</li>
 *   <li>metadataJson may contain only safe IDs, counts, filenames, and status values.</li>
 * </ul>
 */
@Getter
@Builder
public class ProjectActivityEvent {

    /** The user who triggered the action (always receives notification). */
    private final UUID actorUserId;

    /** Optional project owner/maintainer user IDs to also notify. May be empty. */
    private final java.util.List<UUID> additionalRecipientUserIds;

    /** Optional project context. */
    private final UUID projectId;

    private final NotificationType type;
    private final NotificationSeverity severity;
    private final String title;
    private final String message;

    /** Optional frontend deep-link. */
    private final String actionUrl;

    /**
     * Safe metadata JSON string.
     * Allowed: safe IDs, counts, filenames, and status values.
     * Forbidden: JWT, API keys, source code, AI prompts, stack traces.
     */
    private final String metadataJson;
}
