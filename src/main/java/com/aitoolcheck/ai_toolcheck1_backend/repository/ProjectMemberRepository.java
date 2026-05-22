package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectMemberRole;
import com.aitoolcheck.ai_toolcheck1_backend.model.ProjectMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

    boolean existsBySourceProject_IdAndUser_Id(UUID projectId, UUID userId);

    Optional<ProjectMember> findBySourceProject_IdAndUser_Id(UUID projectId, UUID userId);

    List<ProjectMember> findByUser_Id(UUID userId);

    List<ProjectMember> findBySourceProject_Id(UUID projectId);

    boolean existsBySourceProject_IdAndUser_IdAndRoleIn(
            UUID projectId,
            UUID userId,
            Collection<ProjectMemberRole> roles
    );

    // ── Permanent delete support ───────────────────────────────────────────────

    /** Delete all project member rows for a project. Run just before deleting the project row. */
    @Modifying
    @Query("DELETE FROM ProjectMember pm WHERE pm.sourceProject.id = :projectId")
    void deleteBySourceProjectId(@Param("projectId") UUID projectId);
}
