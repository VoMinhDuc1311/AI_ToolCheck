package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.CreateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.PermanentDeleteProjectRequest;
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
import com.aitoolcheck.ai_toolcheck1_backend.repository.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.CurrentUserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceProjectService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.common.GitHubRepositoryUrlParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SourceProjectServiceImpl implements SourceProjectService {

    private final SourceProjectRepository        sourceProjectRepository;
    private final ProjectMemberRepository        projectMemberRepository;
    private final CurrentUserService             currentUserService;
    private final ProjectAccessService           projectAccessService;
    private final TestFailureAnalysisRepository  testFailureAnalysisRepository;
    private final AiJobLogRepository             aiJobLogRepository;
    private final TestResultRepository           testResultRepository;
    private final TestRunItemRepository          testRunItemRepository;
    private final TestRunRepository              testRunRepository;
    private final TestCaseAssertionRepository    testCaseAssertionRepository;
    private final TestCaseInputRepository        testCaseInputRepository;
    private final TestCaseRepository             testCaseRepository;
    private final LegacyInferenceLogRepository   legacyInferenceLogRepository;
    private final EndpointSchemaMapRepository    endpointSchemaMapRepository;
    private final ApiParameterRepository         apiParameterRepository;
    private final ApiEndpointRepository          apiEndpointRepository;
    private final ApiSchemaFieldRepository       apiSchemaFieldRepository;
    private final ApiSchemaRepository            apiSchemaRepository;
    private final ApiDocumentVersionRepository   apiDocumentVersionRepository;
    private final ApiDocumentRepository          apiDocumentRepository;
    private final SourceAnalysisResultRepository sourceAnalysisResultRepository;
    private final SourceFileRepository           sourceFileRepository;
    private final SourceUploadVersionRepository  sourceUploadVersionRepository;

    @Override
    @Transactional
    public SourceProjectDetailResponse create(CreateSourceProjectRequest request) {
        AppUser currentUser = currentUserService.getCurrentUser();
        String projectKey  = request.getProjectKey().trim();
        String projectName = request.getProjectName().trim();

        if (sourceProjectRepository.existsByProjectKey(projectKey)) {
            throw new BadRequestException("Project key already exists");
        }
        if (sourceProjectRepository.existsByProjectName(projectName)) {
            throw new BadRequestException("Project name already exists");
        }

        // Validate and normalise GitHub repository metadata
        String normRepoUrl    = GitHubRepositoryUrlParser.normalise(request.getRepositoryUrl());
        String normRepoBranch = normRepoUrl != null
                ? GitHubRepositoryUrlParser.normaliseBranch(request.getRepositoryBranch())
                : null;

        SourceProject saved = sourceProjectRepository.save(SourceProject.builder()
                .projectKey(projectKey)
                .projectName(projectName)
                .description(trimToNull(request.getDescription()))
                .repositoryUrl(normRepoUrl)
                .repositoryBranch(normRepoBranch)
                .backendType(request.getBackendType())
                .status(ProjectStatus.NEW)
                .ownerUser(currentUser)
                .visibility(ProjectVisibility.PRIVATE)
                .build());

        return mapToDetailResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public SourceProjectDetailResponse getById(UUID id) {
        return mapToDetailResponse(projectAccessService.requireCanViewProject(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceProjectResponse> getAll(boolean includeArchived) {
        AppUser currentUser = currentUserService.getCurrentUser();
        if (currentUser.getRole() == UserRole.ADMIN) {
            List<SourceProject> projects = includeArchived
                    ? sourceProjectRepository.findByDeletedFlagFalseOrderByCreatedAtDesc()
                    : sourceProjectRepository.findByArchivedFlagFalseAndDeletedFlagFalseOrderByCreatedAtDesc();
            return projects.stream().map(p -> mapToResponse(p, currentUser)).toList();
        }
        return accessibleProjects(currentUser, includeArchived)
                .stream()
                .map(p -> mapToResponse(p, currentUser))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceProjectResponse> getMine(boolean includeArchived) {
        AppUser currentUser = currentUserService.getCurrentUser();
        return accessibleProjects(currentUser, includeArchived)
                .stream()
                .filter(p -> isOwnedBy(p, currentUser)
                        || projectMemberRepository.existsBySourceProject_IdAndUser_Id(p.getId(), currentUser.getId()))
                .map(p -> mapToResponse(p, currentUser))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceProjectResponse> getPublic() {
        AppUser currentUser = currentUserService.getCurrentUser();
        return sourceProjectRepository
                .findByVisibilityAndArchivedFlagFalseAndDeletedFlagFalseOrderByCreatedAtDesc(ProjectVisibility.PUBLIC_READ)
                .stream()
                .map(p -> mapToResponse(p, currentUser))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceProjectResponse> getArchived() {
        AppUser currentUser = currentUserService.getCurrentUser();

        if (currentUser.getRole() == UserRole.ADMIN) {
            return sourceProjectRepository
                    .findByArchivedFlagTrueAndDeletedFlagFalseOrderByCreatedAtDesc()
                    .stream()
                    .map(p -> mapToResponse(p, currentUser))
                    .toList();
        }

        Map<UUID, SourceProject> byId = new LinkedHashMap<>();
        sourceProjectRepository
                .findByOwnerUser_IdAndArchivedFlagTrueAndDeletedFlagFalseOrderByCreatedAtDesc(currentUser.getId())
                .forEach(p -> byId.put(p.getId(), p));
        projectMemberRepository.findByUser_Id(currentUser.getId()).stream()
                .map(ProjectMember::getSourceProject)
                .filter(p -> Boolean.TRUE.equals(p.getArchivedFlag()) && !Boolean.TRUE.equals(p.getDeletedFlag()))
                .forEach(p -> byId.put(p.getId(), p));

        return byId.values().stream().map(p -> mapToResponse(p, currentUser)).toList();
    }

    @Override
    @Transactional
    public SourceProjectDetailResponse update(UUID id, UpdateSourceProjectRequest request) {
        SourceProject project = projectAccessService.requireCanManageProject(id);
        String projectName = request.getProjectName().trim();

        if (sourceProjectRepository.existsByProjectNameAndIdNot(projectName, id)) {
            throw new BadRequestException("Project name already exists");
        }

        project.setProjectName(projectName);
        project.setDescription(trimToNull(request.getDescription()));
        project.setBackendType(request.getBackendType());
        project.setStatus(request.getStatus());

        // Validate and normalise GitHub repository metadata
        String normRepoUrl = GitHubRepositoryUrlParser.normalise(request.getRepositoryUrl());
        project.setRepositoryUrl(normRepoUrl);
        // Clear branch when URL is cleared; otherwise normalise branch
        project.setRepositoryBranch(normRepoUrl != null
                ? GitHubRepositoryUrlParser.normaliseBranch(request.getRepositoryBranch())
                : null);

        return mapToDetailResponse(sourceProjectRepository.save(project));
    }

    @Override
    @Transactional
    public SourceProjectDetailResponse updateVisibility(UUID id, UpdateProjectVisibilityRequest request) {
        SourceProject project = projectAccessService.requireCanAdminProject(id);
        project.setVisibility(request.getVisibility());
        return mapToDetailResponse(sourceProjectRepository.save(project));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        archiveProject(id);
    }

    @Override
    @Transactional
    public SourceProjectDetailResponse archiveProject(UUID id) {
        SourceProject project = projectAccessService.requireCanArchiveProject(id);

        if (Boolean.TRUE.equals(project.getArchivedFlag())) {
            return mapToDetailResponse(project);
        }

        AppUser currentUser = currentUserService.getCurrentUser();
        project.setArchivedFlag(true);
        project.setArchivedAt(LocalDateTime.now());
        project.setArchivedBy(currentUser.getId());

        SourceProject saved = sourceProjectRepository.save(project);
        log.info("Project archived: id={}, key={}, by={}", id, project.getProjectKey(), currentUser.getId());
        return mapToDetailResponse(saved);
    }

    @Override
    @Transactional
    public SourceProjectDetailResponse restoreProject(UUID id) {
        SourceProject project = projectAccessService.requireCanRestoreProject(id);

        if (Boolean.TRUE.equals(project.getDeletedFlag())) {
            throw new BadRequestException("Project has been permanently deleted and cannot be restored.");
        }

        if (!Boolean.TRUE.equals(project.getArchivedFlag())) {
            return mapToDetailResponse(project);
        }

        project.setArchivedFlag(false);
        project.setArchivedAt(null);
        project.setArchivedBy(null);

        SourceProject saved = sourceProjectRepository.save(project);
        log.info("Project restored: id={}, key={}", id, project.getProjectKey());
        return mapToDetailResponse(saved);
    }

    @Override
    @Transactional
    public void permanentlyDeleteProject(UUID id, PermanentDeleteProjectRequest request) {
        SourceProject project = projectAccessService.requireCanPermanentDeleteProject(id);
        AppUser currentUser = currentUserService.getCurrentUser();

        if (!project.getProjectKey().equals(request.getConfirmProjectKey())) {
            throw new BadRequestException("confirmProjectKey does not match. Permanent deletion aborted.");
        }

        log.warn("PERMANENT DELETE initiated: id={}, key={}, by={}", id, project.getProjectKey(), currentUser.getId());

        project.setDeletedFlag(true);
        project.setDeletedAt(LocalDateTime.now());
        project.setDeletedBy(currentUser.getId());
        sourceProjectRepository.save(project);

        testFailureAnalysisRepository.deleteByTestRunProjectId(id);
        aiJobLogRepository.deleteBySourceProjectId(id);
        testResultRepository.deleteByTestRunProjectId(id);
        testRunItemRepository.deleteByTestRunProjectId(id);
        testRunRepository.deleteBySourceProjectId(id);
        testCaseAssertionRepository.deleteByTestCaseProjectId(id);
        testCaseInputRepository.deleteByTestCaseProjectId(id);
        testCaseRepository.deleteBySourceProjectId(id);
        legacyInferenceLogRepository.deleteBySourceProjectId(id);
        endpointSchemaMapRepository.deleteByApiEndpoint_SourceProject_Id(id);
        apiParameterRepository.deleteByApiEndpoint_SourceProject_Id(id);
        apiEndpointRepository.deleteBySourceProjectId(id);
        apiSchemaFieldRepository.deleteByApiSchema_SourceProject_Id(id);
        apiSchemaRepository.deleteBySourceProjectId(id);
        apiDocumentVersionRepository.deleteByApiDocumentProjectId(id);
        apiDocumentRepository.deleteBySourceProjectId(id);
        sourceAnalysisResultRepository.deleteBySourceProjectId(id);
        sourceFileRepository.deleteBySourceProjectId(id);
        sourceUploadVersionRepository.deleteBySourceProjectId(id);
        projectMemberRepository.deleteBySourceProjectId(id);
        sourceProjectRepository.deleteById(id);

        log.warn("PERMANENT DELETE completed: id={}, key={}, by={}", id, project.getProjectKey(), currentUser.getId());
    }

    private List<SourceProject> accessibleProjects(AppUser currentUser, boolean includeArchived) {
        Map<UUID, SourceProject> byId = new LinkedHashMap<>();

        if (includeArchived) {
            sourceProjectRepository
                    .findByOwnerUser_IdAndDeletedFlagFalseOrderByCreatedAtDesc(currentUser.getId())
                    .forEach(p -> byId.put(p.getId(), p));
            projectMemberRepository.findByUser_Id(currentUser.getId()).stream()
                    .map(ProjectMember::getSourceProject)
                    .filter(p -> !Boolean.TRUE.equals(p.getDeletedFlag()))
                    .forEach(p -> byId.put(p.getId(), p));
            sourceProjectRepository
                    .findByVisibilityAndDeletedFlagFalseOrderByCreatedAtDesc(ProjectVisibility.PUBLIC_READ)
                    .forEach(p -> byId.put(p.getId(), p));
        } else {
            sourceProjectRepository
                    .findByOwnerUser_IdAndArchivedFlagFalseAndDeletedFlagFalseOrderByCreatedAtDesc(currentUser.getId())
                    .forEach(p -> byId.put(p.getId(), p));
            projectMemberRepository.findByUser_Id(currentUser.getId()).stream()
                    .map(ProjectMember::getSourceProject)
                    .filter(p -> !Boolean.TRUE.equals(p.getArchivedFlag()) && !Boolean.TRUE.equals(p.getDeletedFlag()))
                    .forEach(p -> byId.put(p.getId(), p));
            sourceProjectRepository
                    .findByVisibilityAndArchivedFlagFalseAndDeletedFlagFalseOrderByCreatedAtDesc(ProjectVisibility.PUBLIC_READ)
                    .forEach(p -> byId.put(p.getId(), p));
        }

        return byId.values().stream().toList();
    }

    private boolean isOwnedBy(SourceProject project, AppUser user) {
        return project.getOwnerUser() != null && user.getId().equals(project.getOwnerUser().getId());
    }

    private SourceProjectResponse mapToResponse(SourceProject p, AppUser currentUser) {
        return SourceProjectResponse.builder()
                .id(p.getId())
                .name(p.getProjectName())
                .ownerUserId(p.getOwnerUser() == null ? null : p.getOwnerUser().getId())
                .ownerEmail(p.getOwnerUser() == null ? null : p.getOwnerUser().getEmail())
                .projectKey(p.getProjectKey())
                .projectName(p.getProjectName())
                .backendType(p.getBackendType())
                .status(p.getStatus())
                .visibility(p.getVisibility())
                .repositoryUrl(p.getRepositoryUrl())
                .createdAt(p.getCreatedAt())
                .archivedFlag(p.getArchivedFlag())
                .archivedAt(p.getArchivedAt())
                .currentUserRole(projectAccessService.getCurrentUserProjectRole(p, currentUser))
                .currentUserPermissions(projectAccessService.buildPermissions(p, currentUser))
                .build();
    }

    private SourceProjectDetailResponse mapToDetailResponse(SourceProject p) {
        return mapToDetailResponse(p, currentUserService.getCurrentUser());
    }

    private SourceProjectDetailResponse mapToDetailResponse(SourceProject p, AppUser currentUser) {
        String repoUrl = p.getRepositoryUrl();
        return SourceProjectDetailResponse.builder()
                .id(p.getId())
                .name(p.getProjectName())
                .ownerUserId(p.getOwnerUser() == null ? null : p.getOwnerUser().getId())
                .ownerEmail(p.getOwnerUser() == null ? null : p.getOwnerUser().getEmail())
                .projectKey(p.getProjectKey())
                .projectName(p.getProjectName())
                .description(p.getDescription())
                .repositoryUrl(repoUrl)
                .repositoryBranch(p.getRepositoryBranch())
                .repositoryProvider(GitHubRepositoryUrlParser.deriveProvider(repoUrl))
                .repositoryOwner(GitHubRepositoryUrlParser.extractOwner(repoUrl))
                .repositoryName(GitHubRepositoryUrlParser.extractRepo(repoUrl))
                .backendType(p.getBackendType())
                .status(p.getStatus())
                .visibility(p.getVisibility())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .archivedFlag(p.getArchivedFlag())
                .archivedAt(p.getArchivedAt())
                .currentUserRole(projectAccessService.getCurrentUserProjectRole(p, currentUser))
                .currentUserPermissions(projectAccessService.buildPermissions(p, currentUser))
                .build();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
