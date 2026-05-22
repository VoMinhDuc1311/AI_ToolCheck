package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.TestRunItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TestRunItemRepository extends JpaRepository<TestRunItem, UUID> {

    List<TestRunItem> findByTestRun_IdOrderBySortOrderAsc(UUID testRunId);

    long countByTestRun_Id(UUID testRunId);

    // ── Permanent delete support ───────────────────────────────────────────────

    /**
     * Delete all TestRunItem rows for a project (after TestResult already deleted).
     */
    @Modifying
    @Query("""
        DELETE FROM TestRunItem tri
        WHERE tri.testRun.id IN (
            SELECT run.id FROM TestRun run WHERE run.sourceProject.id = :projectId
        )
    """)
    void deleteByTestRunProjectId(@Param("projectId") UUID projectId);
}
