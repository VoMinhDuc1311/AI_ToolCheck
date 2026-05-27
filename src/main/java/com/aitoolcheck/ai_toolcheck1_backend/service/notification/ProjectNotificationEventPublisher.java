package com.aitoolcheck.ai_toolcheck1_backend.service.notification;

import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationSeverity;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectMemberRole;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.model.ProjectMember;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ProjectMemberRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.CurrentUserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectNotificationEventPublisher {

    private static final Set<ProjectMemberRole> NOTIFIED_PROJECT_ROLES = Set.of(
            ProjectMemberRole.MAINTAINER
    );

    private final ApplicationEventPublisher eventPublisher;
    private final CurrentUserService currentUserService;
    private final SourceProjectRepository sourceProjectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ObjectMapper objectMapper;

    public void publishForCurrentUser(
            UUID projectId,
            NotificationType type,
            NotificationSeverity severity,
            String title,
            String message,
            String actionUrl,
            Map<String, ?> metadata) {

        AppUser actor = currentUserOrNull();
        if (actor == null || actor.getId() == null) {
            log.debug("[ProjectNotification] Skipping {} for projectId={} because no authenticated actor exists",
                    type, projectId);
            return;
        }

        publish(projectId, actor.getId(), type, severity, title, message, actionUrl, metadata);
    }

    public void publishForProjectOwner(
            UUID projectId,
            NotificationType type,
            NotificationSeverity severity,
            String title,
            String message,
            String actionUrl,
            Map<String, ?> metadata) {

        SourceProject project = findProject(projectId);
        if (project == null || project.getOwnerUser() == null || project.getOwnerUser().getId() == null) {
            log.debug("[ProjectNotification] Skipping {} for projectId={} because project owner is unavailable",
                    type, projectId);
            return;
        }

        publish(projectId, project.getOwnerUser().getId(), type, severity, title, message, actionUrl, metadata);
    }

    private void publish(
            UUID projectId,
            UUID actorUserId,
            NotificationType type,
            NotificationSeverity severity,
            String title,
            String message,
            String actionUrl,
            Map<String, ?> metadata) {

        Set<UUID> additionalRecipients = resolveOwnerAndMaintainers(projectId);
        additionalRecipients.remove(actorUserId);

        eventPublisher.publishEvent(ProjectActivityEvent.builder()
                .actorUserId(actorUserId)
                .additionalRecipientUserIds(List.copyOf(additionalRecipients))
                .projectId(projectId)
                .type(type)
                .severity(severity)
                .title(title)
                .message(message)
                .actionUrl(actionUrl)
                .metadataJson(toSafeMetadataJson(metadata))
                .build());
    }

    private Set<UUID> resolveOwnerAndMaintainers(UUID projectId) {
        Set<UUID> recipientIds = new LinkedHashSet<>();
        SourceProject project = findProject(projectId);
        if (project != null && project.getOwnerUser() != null && project.getOwnerUser().getId() != null) {
            recipientIds.add(project.getOwnerUser().getId());
        }

        if (projectId != null) {
            for (ProjectMember member : projectMemberRepository.findBySourceProject_Id(projectId)) {
                if (member.getUser() != null
                        && member.getUser().getId() != null
                        && NOTIFIED_PROJECT_ROLES.contains(member.getRole())) {
                    recipientIds.add(member.getUser().getId());
                }
            }
        }
        return recipientIds;
    }

    private SourceProject findProject(UUID projectId) {
        if (projectId == null) {
            return null;
        }
        return sourceProjectRepository.findById(projectId).orElse(null);
    }

    private AppUser currentUserOrNull() {
        try {
            return currentUserService.getCurrentUser();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String toSafeMetadataJson(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }

        Map<String, Object> safe = new LinkedHashMap<>();
        copySafe(metadata, safe, "projectId");
        copySafe(metadata, safe, "uploadVersionId");
        copySafe(metadata, safe, "analysisResultId");
        copySafe(metadata, safe, "jobId");
        copySafe(metadata, safe, "jobType");
        copySafe(metadata, safe, "status");
        copySafe(metadata, safe, "documentId");
        copySafe(metadata, safe, "versionId");
        copySafe(metadata, safe, "testRunId");
        copySafe(metadata, safe, "filename");
        copySafe(metadata, safe, "cleanedEndpointCount");
        copySafe(metadata, safe, "staleEndpointCount");
        copySafe(metadata, safe, "pathCount");
        copySafe(metadata, safe, "operationCount");

        if (safe.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(safe);
        } catch (JsonProcessingException ex) {
            log.warn("[ProjectNotification] Failed to serialize safe metadata: {}", ex.getMessage());
            return null;
        }
    }

    private void copySafe(Map<String, ?> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value == null) {
            return;
        }
        if (value instanceof UUID || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            target.put(key, value.toString());
        } else {
            String text = value.toString();
            if (!text.isBlank()) {
                target.put(key, text.length() <= 190 ? text : text.substring(0, 190));
            }
        }
    }
}
