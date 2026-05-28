package com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BackendType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectMemberRole;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectVisibility;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectStatus;

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
public class SourceProjectResponse {

    private UUID id;
    private String name;
    private UUID ownerUserId;
    private String ownerEmail;
    private String projectKey;
    private String projectName;
    private BackendType backendType;
    private ProjectStatus status;
    private ProjectVisibility visibility;

    // ── GitHub repository metadata (list-view subset) ───────────────────────
    /** Normalised GitHub repository URL. Null if not configured. */
    private String repositoryUrl;

    private LocalDateTime createdAt;

    // ── Archive lifecycle ──────────────────────────────────────────────────────
    private Boolean archivedFlag;
    private LocalDateTime archivedAt;

    // ── Current-user access context ───────────────────────────────────────────
    /** The authenticated user's explicit member role, or null for ADMIN / project OWNER. */
    private ProjectMemberRole currentUserRole;
    /** Fine-grained capability flags derived from ProjectAccessService rules. */
    private ProjectPermissionResponse currentUserPermissions;
}
