package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectVisibility;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    @Query("""
        SELECT sp
        FROM SourceProject sp
        WHERE sp.deletedFlag = false
          AND (:includeArchived = true OR sp.archivedFlag = false)
          AND (
              sp.ownerUser.id = :userId
              OR sp.visibility = :visibility
              OR EXISTS (
                  SELECT 1 FROM ProjectMember pm
                  WHERE pm.sourceProject.id = sp.id
                    AND pm.user.id = :userId
              )
          )
        ORDER BY sp.createdAt DESC
    """)
    List<SourceProject> findAccessibleProjects(
            @Param("userId") UUID userId,
            @Param("visibility") ProjectVisibility visibility,
            @Param("includeArchived") boolean includeArchived);

    @Query("""
        SELECT sp.id
        FROM SourceProject sp
        WHERE sp.deletedFlag = false
          AND sp.archivedFlag = false
          AND (
              sp.ownerUser.id = :userId
              OR EXISTS (
                  SELECT 1 FROM ProjectMember pm
                  WHERE pm.sourceProject.id = sp.id
                    AND pm.user.id = :userId
              )
          )
    """)
    List<UUID> findActiveNonArchivedOwnedOrMemberProjectIds(@Param("userId") UUID userId);

    @Query("""
        SELECT COUNT(sp) > 0
        FROM SourceProject sp
        WHERE sp.id = :projectId
          AND sp.ownerUser.id = :userId
          AND sp.deletedFlag = false
          AND sp.archivedFlag = false
    """)
    boolean existsActiveNonArchivedByIdAndOwnerUserId(@Param("projectId") UUID projectId, @Param("userId") UUID userId);
}
