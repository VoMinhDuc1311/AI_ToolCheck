package com.aitoolcheck.ai_toolcheck1_backend.service.access;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectMemberRole;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectVisibility;
import com.aitoolcheck.ai_toolcheck1_backend.enums.UserRole;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ForbiddenException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ProjectMemberRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectAccessService {

    private static final Set<ProjectMemberRole> MANAGE_ROLES = Set.of(
            ProjectMemberRole.MAINTAINER,
            ProjectMemberRole.EDITOR
    );

    private static final Set<ProjectMemberRole> TEST_RUN_PREPARE_ROLES = Set.of(
            ProjectMemberRole.MAINTAINER,
            ProjectMemberRole.EDITOR,
            ProjectMemberRole.VIEWER
    );

    private static final Set<ProjectMemberRole> MAINTAINER_ONLY = Set.of(ProjectMemberRole.MAINTAINER);

    private final SourceProjectRepository sourceProjectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public SourceProject requireCanViewProject(UUID projectId) {
        SourceProject project = findProjectOrThrow(projectId);
        AppUser user = currentUserService.getCurrentUser();
        if (!canViewProject(project, user)) {
            throw notFound(projectId);
        }
        return project;
    }

    @Transactional(readOnly = true)
    public SourceProject requireCanManageProject(UUID projectId) {
        SourceProject project = requireCanViewProject(projectId);
        AppUser user = currentUserService.getCurrentUser();
        if (!canManageProject(project, user)) {
            throw forbidden();
        }
        return project;
    }

    @Transactional(readOnly = true)
    public SourceProject requireCanAdminProject(UUID projectId) {
        SourceProject project = requireCanViewProject(projectId);
        AppUser user = currentUserService.getCurrentUser();
        if (!isAdmin(user) && !isOwner(project, user)) {
            throw forbidden();
        }
        return project;
    }

    @Transactional(readOnly = true)
    public SourceProject requireCanCreateTestCase(UUID projectId) {
        return requireMemberRoleOrOwnerAdmin(projectId, MANAGE_ROLES);
    }

    @Transactional(readOnly = true)
    public SourceProject requireCanCreateTestRun(UUID projectId) {
        return requireMemberRoleOrOwnerAdmin(projectId, MANAGE_ROLES);
    }

    @Transactional(readOnly = true)
    public SourceProject requireCanPrepareTestRun(UUID projectId) {
        SourceProject project = requireCanViewProject(projectId);
        AppUser user = currentUserService.getCurrentUser();
        if (isAdmin(user) || isOwner(project, user)
                || isPublicRead(project)
                || hasMemberRole(project.getId(), user.getId(), TEST_RUN_PREPARE_ROLES)) {
            return project;
        }
        throw forbidden();
    }

    @Transactional(readOnly = true)
    public SourceProject requireCanTriggerAiJob(UUID projectId) {
        return requireMemberRoleOrOwnerAdmin(projectId, MAINTAINER_ONLY);
    }

    @Transactional(readOnly = true)
    public SourceProject requireCanDeleteTestCase(UUID projectId) {
        return requireMemberRoleOrOwnerAdmin(projectId, MAINTAINER_ONLY);
    }

    @Transactional(readOnly = true)
    public SourceProject requireCanViewSourceFileContent(UUID projectId) {
        SourceProject project = findProjectOrThrow(projectId);
        AppUser user = currentUserService.getCurrentUser();

        if (canViewPrivateProject(project, user)) {
            return project;
        }

        if (canViewProject(project, user)) {
            throw forbidden();
        }

        throw notFound(projectId);
    }

    @Transactional(readOnly = true)
    public SourceProject requireCanGenerateDocs(UUID projectId) {
        return requireMemberRoleOrOwnerAdmin(projectId, MAINTAINER_ONLY);
    }

    @Transactional(readOnly = true)
    public SourceProject requireCanUploadSource(UUID projectId) {
        return requireMemberRoleOrOwnerAdmin(projectId, MAINTAINER_ONLY);
    }

    @Transactional(readOnly = true)
    public SourceProject requireCanExecuteTestRun(UUID projectId, ExecutionMode executionMode) {
        if (executionMode == null) {
            throw new BadRequestException("executionMode is required");
        }

        return switch (executionMode) {
            case READ_ONLY -> requireMemberRoleOrOwnerAdmin(projectId, MANAGE_ROLES);
            case SAFE_WRITE, FULL_WRITE -> requireMemberRoleOrOwnerAdmin(projectId, MAINTAINER_ONLY);
        };
    }

    @Transactional(readOnly = true)
    public boolean canViewProject(SourceProject project, AppUser user) {
        if (project == null || user == null || user.getId() == null) {
            return false;
        }
        if (isAdmin(user) || isOwner(project, user) || isPublicRead(project)) {
            return true;
        }
        if (project.getId() == null) {
            return false;
        }
        return projectMemberRepository.existsBySourceProject_IdAndUser_Id(project.getId(), user.getId());
    }

    @Transactional(readOnly = true)
    public boolean canManageProject(SourceProject project, AppUser user) {
        if (project == null || user == null || user.getId() == null) {
            return false;
        }
        if (isAdmin(user) || isOwner(project, user)) {
            return true;
        }
        if (project.getId() == null) {
            return false;
        }
        return hasMemberRole(project.getId(), user.getId(), MANAGE_ROLES);
    }

    @Transactional(readOnly = true)
    public boolean canViewPrivateProject(SourceProject project, AppUser user) {
        if (project == null || user == null || user.getId() == null) {
            return false;
        }
        if (isAdmin(user) || isOwner(project, user)) {
            return true;
        }
        return project.getId() != null
                && projectMemberRepository.existsBySourceProject_IdAndUser_Id(project.getId(), user.getId());
    }

    private SourceProject requireMemberRoleOrOwnerAdmin(UUID projectId, Collection<ProjectMemberRole> roles) {
        SourceProject project = requireCanViewProject(projectId);
        AppUser user = currentUserService.getCurrentUser();
        if (isAdmin(user) || isOwner(project, user) || hasMemberRole(project.getId(), user.getId(), roles)) {
            return project;
        }
        throw forbidden();
    }

    private SourceProject findProjectOrThrow(UUID projectId) {
        if (projectId == null) {
            throw new BadRequestException("projectId is required");
        }
        return sourceProjectRepository.findById(projectId)
                .orElseThrow(() -> notFound(projectId));
    }

    private boolean hasMemberRole(UUID projectId, UUID userId, Collection<ProjectMemberRole> roles) {
        return projectId != null
                && userId != null
                && roles != null
                && !roles.isEmpty()
                && projectMemberRepository.existsBySourceProject_IdAndUser_IdAndRoleIn(projectId, userId, roles);
    }

    private boolean isAdmin(AppUser user) {
        return user != null && user.getRole() == UserRole.ADMIN;
    }

    private boolean isOwner(SourceProject project, AppUser user) {
        return project != null
                && project.getOwnerUser() != null
                && project.getOwnerUser().getId() != null
                && user != null
                && project.getOwnerUser().getId().equals(user.getId());
    }

    private boolean isPublicRead(SourceProject project) {
        return project != null && project.getVisibility() == ProjectVisibility.PUBLIC_READ;
    }

    private ResourceNotFoundException notFound(UUID projectId) {
        return new ResourceNotFoundException("SourceProject not found with id: " + projectId);
    }

    private ForbiddenException forbidden() {
        return new ForbiddenException("You do not have permission to perform this action");
    }
}
