package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.TestRunItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TestRunItemRepository extends JpaRepository<TestRunItem, UUID> {

    List<TestRunItem> findByTestRun_IdOrderBySortOrderAsc(UUID testRunId);

    @Query("""
        SELECT DISTINCT item FROM TestRunItem item
        JOIN FETCH item.testRun run
        JOIN FETCH item.testCase testCase
        LEFT JOIN FETCH testCase.testCaseInput input
        LEFT JOIN FETCH item.testResult result
        WHERE run.id = :testRunId
        ORDER BY item.sortOrder ASC
    """)
    List<TestRunItem> findByTestRunIdWithExecutionGraph(@Param("testRunId") UUID testRunId);

    @Query("""
        SELECT item FROM TestRunItem item
        JOIN FETCH item.testRun run
        JOIN FETCH item.testCase testCase
        LEFT JOIN FETCH testCase.testCaseInput input
        LEFT JOIN FETCH item.testResult result
        WHERE item.id = :itemId
    """)
    Optional<TestRunItem> findByIdWithExecutionGraph(@Param("itemId") UUID itemId);

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
