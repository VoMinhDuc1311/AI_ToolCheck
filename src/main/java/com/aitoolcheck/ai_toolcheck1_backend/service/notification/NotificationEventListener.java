package com.aitoolcheck.ai_toolcheck1_backend.service.notification;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectMemberRole;
import com.aitoolcheck.ai_toolcheck1_backend.dto.notification.res.NotificationRealtimePayload;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.model.ProjectMember;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AppUserRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ProjectMemberRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.NotificationRealtimePublisher;
import com.aitoolcheck.ai_toolcheck1_backend.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Handles {@link ProjectActivityEvent} AFTER the originating transaction commits.
 *
 * <p>This ensures:
 * <ul>
 *   <li>Notifications are not created if the triggering transaction rolls back.</li>
 *   <li>All DB writes (project/job/document) are visible before notification is persisted.</li>
 * </ul>
 *
 * <p>Runs in a new transaction so notification failures are isolated from the
 * business transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;
    private final NotificationRealtimePublisher realtimePublisher;
    private final AppUserRepository appUserRepository;
    private final SourceProjectRepository sourceProjectRepository;
    private final ProjectMemberRepository projectMemberRepository;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleProjectActivity(ProjectActivityEvent event) {
        if (event == null || event.getActorUserId() == null) {
            log.warn("[NotificationEventListener] Skipping null event or null actorUserId");
            return;
        }

        try {
            // Resolve actor
            AppUser actor = appUserRepository.findById(event.getActorUserId()).orElse(null);
            if (actor == null) {
                log.warn("[NotificationEventListener] Actor userId={} not found — skipping notification",
                        event.getActorUserId());
                return;
            }

            // Resolve optional project
            SourceProject project = null;
            if (event.getProjectId() != null) {
                project = sourceProjectRepository.findById(event.getProjectId()).orElse(null);
            }

            // Build de-duplicated recipient set: actor + additional recipients
            Set<UUID> recipientIds = new LinkedHashSet<>();
            recipientIds.add(event.getActorUserId());
            if (event.getAdditionalRecipientUserIds() != null) {
                recipientIds.addAll(event.getAdditionalRecipientUserIds());
            }
            recipientIds = filterRecipientsWithProjectAccess(project, event.getActorUserId(), recipientIds);

            List<AppUser> recipients = appUserRepository.findAllById(recipientIds);

            for (AppUser recipient : recipients) {
                UUID notificationId = notificationService.createNotification(
                        recipient,
                        project,
                        event.getType(),
                        event.getSeverity(),
                        event.getTitle(),
                        event.getMessage(),
                        event.getActionUrl(),
                        event.getMetadataJson()
                );

                // Best-effort realtime delivery
                NotificationRealtimePayload payload = NotificationRealtimePayload.builder()
                        .notificationId(notificationId)
                        .projectId(event.getProjectId())
                        .type(event.getType())
                        .severity(event.getSeverity())
                        .title(event.getTitle())
                        .message(event.getMessage())
                        .actionUrl(event.getActionUrl())
                        .createdAt(LocalDateTime.now())
                        .build();

                // The principal name in STOMP is the user's email (from CustomUserDetails.getUsername())
                realtimePublisher.sendToUser(recipient.getEmail(), payload);
            }

        } catch (Exception ex) {
            // Log and swallow — notification failures must never break the business flow
            log.error("[NotificationEventListener] Failed to process ProjectActivityEvent for actor={}: {}",
                    event.getActorUserId(), ex.getMessage());
        }
    }

    private Set<UUID> filterRecipientsWithProjectAccess(SourceProject project, UUID actorUserId, Set<UUID> requestedRecipientIds) {
        if (project == null || project.getId() == null) {
            return requestedRecipientIds;
        }

        Set<UUID> allowedRecipientIds = new LinkedHashSet<>();
        if (actorUserId != null) {
            allowedRecipientIds.add(actorUserId);
        }
        if (project.getOwnerUser() != null && project.getOwnerUser().getId() != null) {
            allowedRecipientIds.add(project.getOwnerUser().getId());
        }
        for (ProjectMember member : projectMemberRepository.findBySourceProject_Id(project.getId())) {
            if (member.getUser() != null
                    && member.getUser().getId() != null
                    && member.getRole() == ProjectMemberRole.MAINTAINER) {
                allowedRecipientIds.add(member.getUser().getId());
            }
        }

        Set<UUID> filtered = new LinkedHashSet<>(requestedRecipientIds);
        filtered.retainAll(allowedRecipientIds);
        return filtered;
    }
}
