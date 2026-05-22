package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectVisibility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SourceProjectRepository extends JpaRepository<SourceProject, UUID> {

    boolean existsByProjectKey(String projectKey);

    boolean existsByProjectName(String projectName);

    boolean existsByProjectNameAndIdNot(String projectName, UUID id);

    // ── ADMIN: see all rows ───────────────────────────────────────────────────
    List<SourceProject> findAllByOrderByCreatedAtDesc();

    // ── Active-only (archivedFlag=false AND deletedFlag=false) ────────────────
    List<SourceProject> findByArchivedFlagFalseAndDeletedFlagFalseOrderByCreatedAtDesc();

    List<SourceProject> findByOwnerUser_IdAndArchivedFlagFalseAndDeletedFlagFalseOrderByCreatedAtDesc(UUID ownerUserId);

    List<SourceProject> findByVisibilityAndArchivedFlagFalseAndDeletedFlagFalseOrderByCreatedAtDesc(
            ProjectVisibility visibility);

    // ── Active or Archived (deletedFlag=false) — for includeArchived=true ─────
    List<SourceProject> findByDeletedFlagFalseOrderByCreatedAtDesc();

    List<SourceProject> findByOwnerUser_IdAndDeletedFlagFalseOrderByCreatedAtDesc(UUID ownerUserId);

    List<SourceProject> findByVisibilityAndDeletedFlagFalseOrderByCreatedAtDesc(ProjectVisibility visibility);

    // ── Archived list ─────────────────────────────────────────────────────────
    List<SourceProject> findByArchivedFlagTrueAndDeletedFlagFalseOrderByCreatedAtDesc();

    List<SourceProject> findByOwnerUser_IdAndArchivedFlagTrueAndDeletedFlagFalseOrderByCreatedAtDesc(UUID ownerUserId);

    // ── Legacy / other ────────────────────────────────────────────────────────
    List<SourceProject> findByOwnerUser_IdOrderByCreatedAtDesc(UUID ownerUserId);

    List<SourceProject> findByVisibilityOrderByCreatedAtDesc(ProjectVisibility visibility);

    boolean existsByIdAndOwnerUser_Id(UUID projectId, UUID ownerUserId);
}
