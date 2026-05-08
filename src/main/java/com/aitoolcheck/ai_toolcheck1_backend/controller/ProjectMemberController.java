package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.MessageResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.projectmember.req.AddProjectMemberRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.projectmember.req.UpdateProjectMemberRoleRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.projectmember.res.ProjectMemberResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.ProjectMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/source-projects/{projectId}/members")
@RequiredArgsConstructor
@Tag(name = "Project Members", description = "Project sharing and member role APIs")
public class ProjectMemberController {

    private final ProjectMemberService projectMemberService;

    @GetMapping
    @Operation(
            summary = "List project members",
            description = "List users shared on a project. Owner or ADMIN only.",
            operationId = "listProjectMembers"
    )
    public ResponseEntity<ApiResponse<List<ProjectMemberResponse>>> getMembers(@PathVariable UUID projectId) {
        return ResponseEntity.ok(success(
                "Project members fetched successfully.",
                projectMemberService.getMembers(projectId)
        ));
    }

    @PostMapping
    @Operation(
            summary = "Add project member",
            description = "Share a project with a selected user. Owner or ADMIN only.",
            operationId = "addProjectMember"
    )
    public ResponseEntity<ApiResponse<ProjectMemberResponse>> addMember(
            @PathVariable UUID projectId,
            @Valid @RequestBody AddProjectMemberRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(success("Project member added successfully.", projectMemberService.addMember(projectId, request)));
    }

    @PatchMapping("/{memberId}")
    @Operation(
            summary = "Update project member role",
            description = "Update a project member role. Owner or ADMIN only.",
            operationId = "updateProjectMember"
    )
    public ResponseEntity<ApiResponse<ProjectMemberResponse>> updateMember(
            @PathVariable UUID projectId,
            @PathVariable UUID memberId,
            @Valid @RequestBody UpdateProjectMemberRoleRequest request
    ) {
        return ResponseEntity.ok(success(
                "Project member updated successfully.",
                projectMemberService.updateMember(projectId, memberId, request)
        ));
    }

    @DeleteMapping("/{memberId}")
    @Operation(
            summary = "Remove project member",
            description = "Remove a shared project member. Owner or ADMIN only.",
            operationId = "removeProjectMember"
    )
    public ResponseEntity<ApiResponse<MessageResponse>> removeMember(
            @PathVariable UUID projectId,
            @PathVariable UUID memberId
    ) {
        projectMemberService.removeMember(projectId, memberId);
        return ResponseEntity.ok(success(
                "Project member removed successfully.",
                MessageResponse.builder().message("Project member removed successfully.").build()
        ));
    }

    private <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .code("SUCCESS")
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
