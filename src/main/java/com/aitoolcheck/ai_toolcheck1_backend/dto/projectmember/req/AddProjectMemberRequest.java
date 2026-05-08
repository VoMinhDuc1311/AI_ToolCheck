package com.aitoolcheck.ai_toolcheck1_backend.dto.projectmember.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectMemberRole;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddProjectMemberRequest {

    @NotNull(message = "userId is required")
    private UUID userId;

    @NotNull(message = "role is required")
    private ProjectMemberRole role;
}
