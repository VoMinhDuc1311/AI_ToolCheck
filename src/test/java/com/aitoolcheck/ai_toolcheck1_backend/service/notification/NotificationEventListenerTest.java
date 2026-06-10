package com.aitoolcheck.ai_toolcheck1_backend.service.notification;

import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationSeverity;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectMemberRole;
import com.aitoolcheck.ai_toolcheck1_backend.dto.notification.res.NotificationResponse;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.model.ProjectMember;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AppUserRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ProjectMemberRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.NotificationRealtimePublisher;
import com.aitoolcheck.ai_toolcheck1_backend.service.NotificationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationEventListenerTest {

    @Test
    void projectActivityCreatesNotificationsForActorOwnerAndMaintainer() {
        NotificationService notificationService = mock(NotificationService.class);
        NotificationRealtimePublisher realtimePublisher = mock(NotificationRealtimePublisher.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        SourceProjectRepository sourceProjectRepository = mock(SourceProjectRepository.class);
        ProjectMemberRepository projectMemberRepository = mock(ProjectMemberRepository.class);

        UUID actorId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID maintainerId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        AppUser actor = user(actorId, "actor@example.com");
        AppUser owner = user(ownerId, "owner@example.com");
        AppUser maintainer = user(maintainerId, "maintainer@example.com");
        SourceProject project = new SourceProject();
        project.setId(projectId);
        project.setOwnerUser(owner);

        ProjectMember member = new ProjectMember();
        member.setRole(ProjectMemberRole.MAINTAINER);
        member.setUser(maintainer);

        when(appUserRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(sourceProjectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(projectMemberRepository.findBySourceProject_Id(projectId)).thenReturn(List.of(member));
        when(appUserRepository.findAllById(any())).thenReturn(List.of(actor, owner, maintainer));
        when(notificationService.createNotification(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(NotificationResponse.builder()
                        .id(UUID.randomUUID())
                        .createdAt(java.time.LocalDateTime.now())
                        .build());

        NotificationEventListener listener = new NotificationEventListener(
                notificationService,
                realtimePublisher,
                appUserRepository,
                sourceProjectRepository,
                projectMemberRepository);

        listener.handleProjectActivity(ProjectActivityEvent.builder()
                .actorUserId(actorId)
                .additionalRecipientUserIds(List.of(ownerId, maintainerId))
                .projectId(projectId)
                .type(NotificationType.OPENAPI_GENERATED)
                .severity(NotificationSeverity.SUCCESS)
                .title("OpenAPI generated")
                .message("Done")
                .build());

        verify(notificationService, times(3)).createNotification(any(), any(), any(), any(), any(), any(), any(), any());
        verify(realtimePublisher, times(3)).sendToUser(any(), any());
    }

    @Test
    void listenerFailureDoesNotPropagate() {
        NotificationService notificationService = mock(NotificationService.class);
        NotificationRealtimePublisher realtimePublisher = mock(NotificationRealtimePublisher.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        SourceProjectRepository sourceProjectRepository = mock(SourceProjectRepository.class);
        ProjectMemberRepository projectMemberRepository = mock(ProjectMemberRepository.class);

        UUID actorId = UUID.randomUUID();
        when(appUserRepository.findById(actorId)).thenReturn(Optional.of(user(actorId, "actor@example.com")));
        when(appUserRepository.findAllById(any())).thenReturn(List.of(user(actorId, "actor@example.com")));
        doThrow(new RuntimeException("db down"))
                .when(notificationService).createNotification(any(), any(), any(), any(), any(), any(), any(), any());

        NotificationEventListener listener = new NotificationEventListener(
                notificationService,
                realtimePublisher,
                appUserRepository,
                sourceProjectRepository,
                projectMemberRepository);

        assertDoesNotThrow(() -> listener.handleProjectActivity(ProjectActivityEvent.builder()
                .actorUserId(actorId)
                .type(NotificationType.INFO)
                .severity(NotificationSeverity.INFO)
                .title("Title")
                .message("Message")
                .build()));
    }

    private AppUser user(UUID id, String email) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setEmail(email);
        return user;
    }
}
