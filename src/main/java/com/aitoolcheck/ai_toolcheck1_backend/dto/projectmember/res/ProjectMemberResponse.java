package com.aitoolcheck.ai_toolcheck1_backend.dto.projectmember.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectMemberRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectMemberResponse {

    private UUID id;
    private UUID projectId;
    private UUID userId;
    private String userEmail;
    private String userFullName;
    private ProjectMemberRole role;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
