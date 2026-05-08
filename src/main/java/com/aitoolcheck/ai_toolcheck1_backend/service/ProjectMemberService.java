package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.projectmember.req.AddProjectMemberRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.projectmember.req.UpdateProjectMemberRoleRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.projectmember.res.ProjectMemberResponse;

import java.util.List;
import java.util.UUID;

public interface ProjectMemberService {

    List<ProjectMemberResponse> getMembers(UUID projectId);

    ProjectMemberResponse addMember(UUID projectId, AddProjectMemberRequest request);

    ProjectMemberResponse updateMember(UUID projectId, UUID memberId, UpdateProjectMemberRoleRequest request);

    void removeMember(UUID projectId, UUID memberId);
}
