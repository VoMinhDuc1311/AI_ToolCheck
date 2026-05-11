package com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Boolean capability flags for the current authenticated user on a specific project.
 * Values are computed from ProjectAccessService rules and must never be derived
 * independently; always use ProjectAccessService#buildPermissions to construct this DTO.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectPermissionResponse {

    /** User can view/read the project and its non-raw content. */
    private boolean canViewProject;

    /** User can view raw source file content (requires direct membership/ownership, not just PUBLIC_READ). */
    private boolean canViewSourceFileContent;

    /** User can edit project metadata (name, description, type, status). */
    private boolean canManageProject;

    /** User can add / remove / update project members. Owner or ADMIN only. */
    private boolean canManageMembers;

    /** User can change project visibility setting. Owner or ADMIN only. */
    private boolean canUpdateVisibility;

    /** User can upload source files. MAINTAINER, OWNER, or ADMIN only. */
    private boolean canUploadSource;

    /** User can trigger AI doc generation. MAINTAINER, OWNER, or ADMIN only. */
    private boolean canGenerateDocs;

    /** User can trigger AI job runs. MAINTAINER, OWNER, or ADMIN only. */
    private boolean canTriggerAiJob;

    /** User can create or update test cases. EDITOR, MAINTAINER, OWNER, or ADMIN. */
    private boolean canCreateTestCase;

    /** User can create a test run. EDITOR, MAINTAINER, OWNER, or ADMIN. */
    private boolean canCreateTestRun;

    /** User can prepare/preview a test run (read-only execution). All members + PUBLIC_READ + OWNER + ADMIN. */
    private boolean canPrepareTestRun;
}
