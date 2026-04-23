package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.CreateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.UpdateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceProjectDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceProjectResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectStatus;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SourceProjectServiceImpl implements SourceProjectService {

    private final SourceProjectRepository sourceProjectRepository;

    @Override
    public SourceProjectDetailResponse create(CreateSourceProjectRequest request) {
        validateCreateRequest(request);

        String projectKey = request.getProjectKey().trim();
        String projectName = request.getProjectName().trim();

        if (sourceProjectRepository.existsByProjectKey(projectKey)) {
            throw new RuntimeException("Project key already exists");
        }

        if (sourceProjectRepository.existsByProjectName(projectName)) {
            throw new RuntimeException("Project name already exists");
        }

        SourceProject sourceProject = SourceProject.builder()
                .projectKey(projectKey)
                .projectName(projectName)
                .description(trimToNull(request.getDescription()))
                .backendType(request.getBackendType())
                .status(ProjectStatus.NEW)
                .build();

        SourceProject savedProject = sourceProjectRepository.save(sourceProject);
        return mapToDetailResponse(savedProject);
    }

    @Override
    public SourceProjectDetailResponse getById(UUID id) {
        SourceProject sourceProject = sourceProjectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Source project not found with id: " + id));

        return mapToDetailResponse(sourceProject);
    }

    @Override
    public List<SourceProjectResponse> getAll() {
        return sourceProjectRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public SourceProjectDetailResponse update(UUID id, UpdateSourceProjectRequest request) {
        validateUpdateRequest(request);

        SourceProject sourceProject = sourceProjectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Source project not found with id: " + id));

        String projectName = request.getProjectName().trim();

        if (sourceProjectRepository.existsByProjectNameAndIdNot(projectName, id)) {
            throw new RuntimeException("Project name already exists");
        }

        sourceProject.setProjectName(projectName);
        sourceProject.setDescription(trimToNull(request.getDescription()));
        sourceProject.setBackendType(request.getBackendType());
        sourceProject.setStatus(request.getStatus());

        SourceProject updatedProject = sourceProjectRepository.save(sourceProject);
        return mapToDetailResponse(updatedProject);
    }

    @Override
    public void delete(UUID id) {
        SourceProject sourceProject = sourceProjectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Source project not found with id: " + id));

        sourceProjectRepository.delete(sourceProject);
    }

    private void validateCreateRequest(CreateSourceProjectRequest request) {
        if (request == null) {
            throw new RuntimeException("Create request must not be null");
        }

        if (isBlank(request.getProjectKey())) {
            throw new RuntimeException("projectKey must not be blank");
        }

        if (request.getProjectKey().trim().length() > 100) {
            throw new RuntimeException("projectKey must be at most 100 characters");
        }

        if (isBlank(request.getProjectName())) {
            throw new RuntimeException("projectName must not be blank");
        }

        if (request.getProjectName().trim().length() > 255) {
            throw new RuntimeException("projectName must be at most 255 characters");
        }

        if (request.getDescription() != null && request.getDescription().trim().length() > 1000) {
            throw new RuntimeException("description must be at most 1000 characters");
        }

        if (request.getBackendType() == null) {
            throw new RuntimeException("backendType must not be null");
        }
    }

    private void validateUpdateRequest(UpdateSourceProjectRequest request) {
        if (request == null) {
            throw new RuntimeException("Update request must not be null");
        }

        if (isBlank(request.getProjectName())) {
            throw new RuntimeException("projectName must not be blank");
        }

        if (request.getProjectName().trim().length() > 255) {
            throw new RuntimeException("projectName must be at most 255 characters");
        }

        if (request.getDescription() != null && request.getDescription().trim().length() > 1000) {
            throw new RuntimeException("description must be at most 1000 characters");
        }

        if (request.getBackendType() == null) {
            throw new RuntimeException("backendType must not be null");
        }

        if (request.getStatus() == null) {
            throw new RuntimeException("status must not be null");
        }
    }

    private SourceProjectResponse mapToResponse(SourceProject sourceProject) {
        return SourceProjectResponse.builder()
                .id(sourceProject.getId())
                .projectKey(sourceProject.getProjectKey())
                .projectName(sourceProject.getProjectName())
                .backendType(sourceProject.getBackendType())
                .status(sourceProject.getStatus())
                .createdAt(sourceProject.getCreatedAt())
                .build();
    }

    private SourceProjectDetailResponse mapToDetailResponse(SourceProject sourceProject) {
        return SourceProjectDetailResponse.builder()
                .id(sourceProject.getId())
                .projectKey(sourceProject.getProjectKey())
                .projectName(sourceProject.getProjectName())
                .description(sourceProject.getDescription())
                .backendType(sourceProject.getBackendType())
                .status(sourceProject.getStatus())
                .createdAt(sourceProject.getCreatedAt())
                .updatedAt(sourceProject.getUpdatedAt())
                .build();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}