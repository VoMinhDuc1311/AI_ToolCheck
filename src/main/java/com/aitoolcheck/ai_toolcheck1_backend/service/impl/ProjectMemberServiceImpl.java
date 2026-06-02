package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.projectmember.req.AddProjectMemberRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.projectmember.req.UpdateProjectMemberRoleRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.projectmember.res.ProjectMemberResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectMemberRole;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectVisibility;
import com.aitoolcheck.ai_toolcheck1_backend.enums.UserStatus;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationSeverity;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationType;
import com.aitoolcheck.ai_toolcheck1_backend.service.notification.ProjectNotificationEventPublisher;

@Service
@RequiredArgsConstructor
public class ProjectMemberServiceImpl implements ProjectMemberService {

    private final ProjectMemberRepository projectMemberRepository;
    private final SourceProjectRepository sourceProjectRepository;
    private final AppUserRepository appUserRepository;
    private final ProjectAccessService projectAccessService;
    private final ProjectNotificationEventPublisher notificationEventPublisher;

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

        // --- Step A: Validate identity input ---
        boolean hasUserId = request.getUserId() != null;
        boolean hasUserEmail = request.getUserEmail() != null && !request.getUserEmail().isBlank();

        if (hasUserId && hasUserEmail) {
            throw new BadRequestException("Provide either userId or userEmail, not both");
        }
        if (!hasUserId && !hasUserEmail) {
            throw new BadRequestException("Either userId or userEmail is required");
        }

        // --- Step B: Resolve target user ---
        AppUser user;
        if (hasUserId) {
            user = appUserRepository.findById(request.getUserId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "AppUser not found with id: " + request.getUserId()));
        } else {
            String normalizedEmail = request.getUserEmail().trim().toLowerCase(Locale.ROOT);
            user = appUserRepository.findByEmailIgnoreCase(normalizedEmail)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "AppUser not found with email: " + normalizedEmail));
        }

        // --- Step C: Reject non-ACTIVE users ---
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BadRequestException("Only active users can be added as project members");
        }

        // --- Step D: Existing business checks ---
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

        ProjectMember saved = projectMemberRepository.save(member);

        try {
            notificationEventPublisher.publishForCurrentUserAndSpecificRecipients(
                    projectId,
                    List.of(user.getId()),
                    NotificationType.PROJECT_MEMBER_ADDED,
                    NotificationSeverity.INFO,
                    "New member added",
                    "User " + user.getFullName() + " has been added to project " + sourceProject.getProjectName()
                            + " with role " + request.getRole().name() + ".",
                    "/source-projects/" + projectId,
                    Map.of("projectId", projectId));
        } catch (Exception ex) {
            // Log and swallow so notification failures do not rollback project member
            // additions
        }

        return toResponse(saved);
    }

    @Override
    @Transactional
    public ProjectMemberResponse updateMember(UUID projectId, UUID memberId, UpdateProjectMemberRoleRequest request) {
        projectAccessService.requireCanAdminProject(projectId);
        rejectOwnerRole(request.getRole());

        ProjectMember member = findMemberInProject(projectId, memberId);
        member.setRole(request.getRole());
        ProjectMember saved = projectMemberRepository.save(member);

        try {
            notificationEventPublisher.publishForCurrentUserAndSpecificRecipients(
                    projectId,
                    List.of(member.getUser().getId()),
                    NotificationType.PROJECT_ROLE_UPDATED,
                    NotificationSeverity.INFO,
                    "Project role updated",
                    "User " + member.getUser().getFullName() + "'s role in project "
                            + member.getSourceProject().getProjectName() + " has been updated to "
                            + request.getRole().name() + ".",
                    "/source-projects/" + projectId,
                    Map.of("projectId", projectId));
        } catch (Exception ex) {
            // Log and swallow
        }

        return toResponse(saved);
    }

    @Override
    @Transactional
    public void removeMember(UUID projectId, UUID memberId) {
        SourceProject project = projectAccessService.requireCanAdminProject(projectId);
        ProjectMember member = findMemberInProject(projectId, memberId);
        AppUser user = member.getUser();
        String projectName = member.getSourceProject().getProjectName();
        projectMemberRepository.delete(member);
        projectMemberRepository.flush(); // ensure DELETE is flushed so the count below is accurate

        // P1: auto-transition SHARED → PRIVATE when the last regular member is removed
        if (project.getVisibility() == ProjectVisibility.SHARED) {
            long remainingCount = projectMemberRepository.countBySourceProject_Id(projectId);
            if (remainingCount == 0) {
                project.setVisibility(ProjectVisibility.PRIVATE);
                sourceProjectRepository.save(project);
            }
        }
        // PUBLIC_READ and PRIVATE visibility remain unchanged

        try {
            notificationEventPublisher.publishForCurrentUserAndSpecificRecipients(
                    projectId,
                    List.of(user.getId()),
                    NotificationType.PROJECT_MEMBER_REMOVED,
                    NotificationSeverity.INFO,
                    "Member removed",
                    "User " + user.getFullName() + " has been removed from project " + projectName + ".",
                    "/source-projects/" + projectId,
                    Map.of("projectId", projectId));
        } catch (Exception ex) {
            // Log and swallow
        }
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
