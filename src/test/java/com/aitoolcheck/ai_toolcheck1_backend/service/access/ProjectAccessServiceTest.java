package com.aitoolcheck.ai_toolcheck1_backend.service.access;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectMemberRole;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectVisibility;
import com.aitoolcheck.ai_toolcheck1_backend.enums.UserRole;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ForbiddenException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ProjectMemberRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectAccessServiceTest {

    @Mock private SourceProjectRepository sourceProjectRepository;
    @Mock private ProjectMemberRepository projectMemberRepository;
    @Mock private CurrentUserService currentUserService;

    private ProjectAccessService service;

    @BeforeEach
    void setUp() {
        service = new ProjectAccessService(sourceProjectRepository, projectMemberRepository, currentUserService);
    }

    @Test
    void maintainerCanDeleteTestCase() {
        UUID projectId = UUID.randomUUID();
        AppUser user = user(UUID.randomUUID(), UserRole.MEMBER);
        SourceProject project = project(projectId, user(UUID.randomUUID(), UserRole.MEMBER));

        when(sourceProjectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(currentUserService.getCurrentUser()).thenReturn(user);
        when(projectMemberRepository.existsBySourceProject_IdAndUser_Id(projectId, user.getId())).thenReturn(true);
        when(projectMemberRepository.existsBySourceProject_IdAndUser_IdAndRoleIn(eq(projectId), eq(user.getId()), any(Collection.class)))
                .thenReturn(true);

        assertDoesNotThrow(() -> service.requireCanDeleteTestCase(projectId));
    }

    @Test
    void viewerCannotDeleteTestCase() {
        UUID projectId = UUID.randomUUID();
        AppUser user = user(UUID.randomUUID(), UserRole.MEMBER);
        SourceProject project = project(projectId, user(UUID.randomUUID(), UserRole.MEMBER));

        when(sourceProjectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(currentUserService.getCurrentUser()).thenReturn(user);
        when(projectMemberRepository.existsBySourceProject_IdAndUser_Id(projectId, user.getId())).thenReturn(true);
        when(projectMemberRepository.existsBySourceProject_IdAndUser_IdAndRoleIn(eq(projectId), eq(user.getId()), any(Collection.class)))
                .thenReturn(false);

        assertThrows(ForbiddenException.class, () -> service.requireCanDeleteTestCase(projectId));
    }

    @Test
    void outsiderCannotDeleteTestCase() {
        UUID projectId = UUID.randomUUID();
        AppUser user = user(UUID.randomUUID(), UserRole.MEMBER);
        SourceProject project = project(projectId, user(UUID.randomUUID(), UserRole.MEMBER));

        when(sourceProjectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(currentUserService.getCurrentUser()).thenReturn(user);
        when(projectMemberRepository.existsBySourceProject_IdAndUser_Id(projectId, user.getId())).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> service.requireCanDeleteTestCase(projectId));
    }

    @Test
    void adminCanDeleteTestCase() {
        UUID projectId = UUID.randomUUID();
        AppUser admin = user(UUID.randomUUID(), UserRole.ADMIN);
        SourceProject project = project(projectId, user(UUID.randomUUID(), UserRole.MEMBER));

        when(sourceProjectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(currentUserService.getCurrentUser()).thenReturn(admin);

        assertDoesNotThrow(() -> service.requireCanDeleteTestCase(projectId));
    }

    private SourceProject project(UUID id, AppUser owner) {
        SourceProject project = new SourceProject();
        project.setId(id);
        project.setOwnerUser(owner);
        project.setVisibility(ProjectVisibility.PRIVATE);
        return project;
    }

    private AppUser user(UUID id, UserRole role) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setRole(role);
        return user;
    }
}
