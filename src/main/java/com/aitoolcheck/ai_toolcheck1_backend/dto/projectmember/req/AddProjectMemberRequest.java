package com.aitoolcheck.ai_toolcheck1_backend.dto.projectmember.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectMemberRole;
import jakarta.validation.constraints.Email;
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

    // Optional: provide either userId or userEmail, not both
    private UUID userId;

    @Email(message = "userEmail must be a valid email address")
    private String userEmail;

    @NotNull(message = "role is required")
    private ProjectMemberRole role;
}

