package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.CreateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.UpdateProjectVisibilityRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.UpdateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceProjectDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceProjectResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectVisibility;
import com.aitoolcheck.ai_toolcheck1_backend.enums.UserRole;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.model.ProjectMember;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ProjectMemberRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.CurrentUserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceProjectService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SourceProjectServiceImpl implements SourceProjectService {

    private final SourceProjectRepository sourceProjectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final CurrentUserService currentUserService;
    private final ProjectAccessService projectAccessService;

    @Override
    @Transactional
    public SourceProjectDetailResponse create(CreateSourceProjectRequest request) {
        AppUser currentUser = currentUserService.getCurrentUser();
        String projectKey = request.getProjectKey().trim();
        String projectName = request.getProjectName().trim();

        if (sourceProjectRepository.existsByProjectKey(projectKey)) {
            throw new BadRequestException("Project key already exists");
        }

        if (sourceProjectRepository.existsByProjectName(projectName)) {
            throw new BadRequestException("Project name already exists");
        }

        SourceProject sourceProject = SourceProject.builder()
                .projectKey(projectKey)
                .projectName(projectName)
                .description(trimToNull(request.getDescription()))
                .backendType(request.getBackendType())
                .status(ProjectStatus.NEW)
                .ownerUser(currentUser)
                .visibility(ProjectVisibility.PRIVATE)
                .build();

        SourceProject savedProject = sourceProjectRepository.save(sourceProject);
        return mapToDetailResponse(savedProject);
    }

    @Override
    @Transactional(readOnly = true)
    public SourceProjectDetailResponse getById(UUID id) {
        SourceProject sourceProject = projectAccessService.requireCanViewProject(id);
        return mapToDetailResponse(sourceProject);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceProjectResponse> getAll() {
        AppUser currentUser = currentUserService.getCurrentUser();
        if (currentUser.getRole() == UserRole.ADMIN) {
            return sourceProjectRepository.findAllByOrderByCreatedAtDesc()
                    .stream()
                    .map(p -> mapToResponse(p, currentUser))
                    .toList();
        }

        return accessibleProjects(currentUser)
                .stream()
                .map(p -> mapToResponse(p, currentUser))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceProjectResponse> getMine() {
        AppUser currentUser = currentUserService.getCurrentUser();
        return accessibleProjects(currentUser)
                .stream()
                .filter(project -> isOwnedBy(project, currentUser)
                        || projectMemberRepository.existsBySourceProject_IdAndUser_Id(project.getId(), currentUser.getId()))
                .map(p -> mapToResponse(p, currentUser))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceProjectResponse> getPublic() {
        AppUser currentUser = currentUserService.getCurrentUser();
        return sourceProjectRepository.findByVisibilityOrderByCreatedAtDesc(ProjectVisibility.PUBLIC_READ)
                .stream()
                .map(p -> mapToResponse(p, currentUser))
                .toList();
    }

    @Override
    @Transactional
    public SourceProjectDetailResponse update(UUID id, UpdateSourceProjectRequest request) {
        SourceProject sourceProject = projectAccessService.requireCanManageProject(id);
        String projectName = request.getProjectName().trim();

        if (sourceProjectRepository.existsByProjectNameAndIdNot(projectName, id)) {
            throw new BadRequestException("Project name already exists");
        }

        sourceProject.setProjectName(projectName);
        sourceProject.setDescription(trimToNull(request.getDescription()));
        sourceProject.setBackendType(request.getBackendType());
        sourceProject.setStatus(request.getStatus());

        SourceProject updatedProject = sourceProjectRepository.save(sourceProject);
        return mapToDetailResponse(updatedProject);
    }

    @Override
    @Transactional
    public SourceProjectDetailResponse updateVisibility(UUID id, UpdateProjectVisibilityRequest request) {
        SourceProject sourceProject = projectAccessService.requireCanAdminProject(id);
        sourceProject.setVisibility(request.getVisibility());
        SourceProject updatedProject = sourceProjectRepository.save(sourceProject);
        return mapToDetailResponse(updatedProject);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        SourceProject sourceProject = projectAccessService.requireCanAdminProject(id);
        sourceProjectRepository.delete(sourceProject);
    }

    private List<SourceProject> accessibleProjects(AppUser currentUser) {
        Map<UUID, SourceProject> projectsById = new LinkedHashMap<>();

        sourceProjectRepository.findByOwnerUser_IdOrderByCreatedAtDesc(currentUser.getId())
                .forEach(project -> projectsById.put(project.getId(), project));
        projectMemberRepository.findByUser_Id(currentUser.getId()).stream()
                .map(ProjectMember::getSourceProject)
                .forEach(project -> projectsById.put(project.getId(), project));
        sourceProjectRepository.findByVisibilityOrderByCreatedAtDesc(ProjectVisibility.PUBLIC_READ)
                .forEach(project -> projectsById.put(project.getId(), project));

        return projectsById.values().stream().toList();
    }

    private boolean isOwnedBy(SourceProject project, AppUser user) {
        return project.getOwnerUser() != null && user.getId().equals(project.getOwnerUser().getId());
    }

    private SourceProjectResponse mapToResponse(SourceProject sourceProject) {
        return mapToResponse(sourceProject, currentUserService.getCurrentUser());
    }

    private SourceProjectResponse mapToResponse(SourceProject sourceProject, AppUser currentUser) {
        return SourceProjectResponse.builder()
                .id(sourceProject.getId())
                .name(sourceProject.getProjectName())
                .ownerUserId(sourceProject.getOwnerUser() == null ? null : sourceProject.getOwnerUser().getId())
                .ownerEmail(sourceProject.getOwnerUser() == null ? null : sourceProject.getOwnerUser().getEmail())
                .projectKey(sourceProject.getProjectKey())
                .projectName(sourceProject.getProjectName())
                .backendType(sourceProject.getBackendType())
                .status(sourceProject.getStatus())
                .visibility(sourceProject.getVisibility())
                .createdAt(sourceProject.getCreatedAt())
                .currentUserRole(projectAccessService.getCurrentUserProjectRole(sourceProject, currentUser))
                .currentUserPermissions(projectAccessService.buildPermissions(sourceProject, currentUser))
                .build();
    }

    private SourceProjectDetailResponse mapToDetailResponse(SourceProject sourceProject) {
        return mapToDetailResponse(sourceProject, currentUserService.getCurrentUser());
    }

    private SourceProjectDetailResponse mapToDetailResponse(SourceProject sourceProject, AppUser currentUser) {
        return SourceProjectDetailResponse.builder()
                .id(sourceProject.getId())
                .name(sourceProject.getProjectName())
                .ownerUserId(sourceProject.getOwnerUser() == null ? null : sourceProject.getOwnerUser().getId())
                .ownerEmail(sourceProject.getOwnerUser() == null ? null : sourceProject.getOwnerUser().getEmail())
                .projectKey(sourceProject.getProjectKey())
                .projectName(sourceProject.getProjectName())
                .description(sourceProject.getDescription())
                .backendType(sourceProject.getBackendType())
                .status(sourceProject.getStatus())
                .visibility(sourceProject.getVisibility())
                .createdAt(sourceProject.getCreatedAt())
                .updatedAt(sourceProject.getUpdatedAt())
                .currentUserRole(projectAccessService.getCurrentUserProjectRole(sourceProject, currentUser))
                .currentUserPermissions(projectAccessService.buildPermissions(sourceProject, currentUser))
                .build();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
