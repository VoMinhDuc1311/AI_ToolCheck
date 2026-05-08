package com.aitoolcheck.ai_toolcheck1_backend.dto.projectmember.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectMemberRole;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateProjectMemberRoleRequest {

    @NotNull(message = "role is required")
    private ProjectMemberRole role;
}
