package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.CreateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.UpdateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceProjectDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceProjectResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectStatus;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
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
                .build();

        SourceProject savedProject = sourceProjectRepository.save(sourceProject);
        return mapToDetailResponse(savedProject);
    }

    @Override
    public SourceProjectDetailResponse getById(UUID id) {
        SourceProject sourceProject = sourceProjectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Source project not found with id: " + id));

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
        SourceProject sourceProject = sourceProjectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Source project not found with id: " + id));

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
    public void delete(UUID id) {
        SourceProject sourceProject = sourceProjectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Source project not found with id: " + id));

        sourceProjectRepository.delete(sourceProject);
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

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}