package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.TestRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TestRunRepository extends JpaRepository<TestRun, UUID> {

    List<TestRun> findBySourceProject_IdOrderByCreatedAtDesc(UUID projectId);

    List<TestRun> findByRunStatusAndCreatedAtBefore(
            com.aitoolcheck.ai_toolcheck1_backend.enums.RunStatus runStatus,
            java.time.LocalDateTime createdBefore);

    // ── Permanent delete support ───────────────────────────────────────────────

    /** Delete all TestRun rows for a project (after items already deleted). */
    @Modifying
    @Query("DELETE FROM TestRun run WHERE run.sourceProject.id = :projectId")
    void deleteBySourceProjectId(@Param("projectId") UUID projectId);

    long countBySourceProject_Id(UUID projectId);

    long countBySourceProject_IdAndRunStatus(UUID projectId, com.aitoolcheck.ai_toolcheck1_backend.enums.RunStatus runStatus);
}
