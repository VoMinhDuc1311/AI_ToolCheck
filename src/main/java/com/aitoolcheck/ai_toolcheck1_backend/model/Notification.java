package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationSeverity;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persistent notification record.
 *
 * <p>A notification is created after a significant business event and stored
 * in the DB so that offline users see it when they next open the app.
 *
 * <p>Rules:
 * <ul>
 *   <li>recipient_user_id — owner of the notification; only they can read it.</li>
 *   <li>project_id — optional; null for user-scope notifications.</li>
 *   <li>metadata_json — safe subset of context (projectId, jobId, etc.); MUST NOT
 *       contain JWT tokens, API keys, source code, or AI prompts.</li>
 * </ul>
 */
@Entity
@Table(
        name = "notification",
        indexes = {
                @Index(name = "idx_notification_recipient_created_at",
                        columnList = "recipient_user_id, created_at DESC"),
                @Index(name = "idx_notification_recipient_read",
                        columnList = "recipient_user_id, read_flag"),
                @Index(name = "idx_notification_project",
                        columnList = "project_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_user_id", nullable = false)
    private AppUser recipientUser;

    /** Nullable — null for user-scope notifications without a specific project. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private SourceProject project;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 80)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private NotificationSeverity severity;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "message", nullable = false, length = 1000)
    private String message;

    /** Optional deep-link URL the frontend can navigate to. */
    @Column(name = "action_url", length = 500)
    private String actionUrl;

    /**
     * Safe metadata in JSON format. Allowed fields: projectId, jobId, documentId, testRunId.
     * Must NOT contain JWT, API keys, source code, AI prompts, or stack traces.
     */
    @Column(name = "metadata_json", columnDefinition = "JSON")
    private String metadataJson;

    @Column(name = "read_flag", nullable = false)
    @Builder.Default
    private Boolean readFlag = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.readFlag == null) {
            this.readFlag = false;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
