package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.projectmember.req.AddProjectMemberRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.projectmember.req.UpdateProjectMemberRoleRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.projectmember.res.ProjectMemberResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectMemberRole;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectVisibility;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.model.ProjectMember;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AppUserRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ProjectMemberRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.ProjectMemberService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectMemberServiceImpl implements ProjectMemberService {

    private final ProjectMemberRepository projectMemberRepository;
    private final SourceProjectRepository sourceProjectRepository;
    private final AppUserRepository appUserRepository;
    private final ProjectAccessService projectAccessService;

    @Override
    @Transactional(readOnly = true)
    public List<ProjectMemberResponse> getMembers(UUID projectId) {
        projectAccessService.requireCanAdminProject(projectId);
        return projectMemberRepository.findBySourceProject_Id(projectId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public ProjectMemberResponse addMember(UUID projectId, AddProjectMemberRequest request) {
        SourceProject sourceProject = projectAccessService.requireCanAdminProject(projectId);
        rejectOwnerRole(request.getRole());

        AppUser user = appUserRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("AppUser not found with id: " + request.getUserId()));

        if (sourceProject.getOwnerUser() != null && sourceProject.getOwnerUser().getId().equals(user.getId())) {
            throw new BadRequestException("Project owner cannot be added as a project member");
        }

        if (projectMemberRepository.existsBySourceProject_IdAndUser_Id(projectId, user.getId())) {
            throw new BadRequestException("Project member already exists for this user");
        }

        ProjectMember member = ProjectMember.builder()
                .sourceProject(sourceProject)
                .user(user)
                .role(request.getRole())
                .build();

        if (sourceProject.getVisibility() == ProjectVisibility.PRIVATE) {
            sourceProject.setVisibility(ProjectVisibility.SHARED);
            sourceProjectRepository.save(sourceProject);
        }

        return toResponse(projectMemberRepository.save(member));
    }

    @Override
    @Transactional
    public ProjectMemberResponse updateMember(UUID projectId, UUID memberId, UpdateProjectMemberRoleRequest request) {
        projectAccessService.requireCanAdminProject(projectId);
        rejectOwnerRole(request.getRole());

        ProjectMember member = findMemberInProject(projectId, memberId);
        member.setRole(request.getRole());
        return toResponse(projectMemberRepository.save(member));
    }

    @Override
    @Transactional
    public void removeMember(UUID projectId, UUID memberId) {
        projectAccessService.requireCanAdminProject(projectId);
        ProjectMember member = findMemberInProject(projectId, memberId);
        projectMemberRepository.delete(member);
    }

    private ProjectMember findMemberInProject(UUID projectId, UUID memberId) {
        ProjectMember member = projectMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectMember not found with id: " + memberId));

        if (!projectId.equals(member.getSourceProject().getId())) {
            throw new ResourceNotFoundException("ProjectMember not found with id: " + memberId);
        }
        return member;
    }

    private void rejectOwnerRole(ProjectMemberRole role) {
        if (role == ProjectMemberRole.OWNER) {
            throw new BadRequestException("OWNER role cannot be assigned through project member API");
        }
    }

    private ProjectMemberResponse toResponse(ProjectMember member) {
        AppUser user = member.getUser();
        return ProjectMemberResponse.builder()
                .id(member.getId())
                .projectId(member.getSourceProject().getId())
                .userId(user.getId())
                .userEmail(user.getEmail())
                .userFullName(user.getFullName())
                .role(member.getRole())
                .createdAt(member.getCreatedAt())
                .updatedAt(member.getUpdatedAt())
                .build();
    }
}
