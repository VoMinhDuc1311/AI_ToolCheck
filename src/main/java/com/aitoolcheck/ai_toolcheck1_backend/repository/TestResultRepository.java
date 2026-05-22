package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.TestResult;
import com.aitoolcheck.ai_toolcheck1_backend.repository.projection.TestResultStatusCountProjection;
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
public interface TestResultRepository extends JpaRepository<TestResult, UUID> {

    @Query("SELECT tr.resultStatus AS status, COUNT(tr) AS total FROM TestResult tr GROUP BY tr.resultStatus")
    List<TestResultStatusCountProjection> getStatusStatistics();

    Optional<TestResult> findByTestRunItem_Id(UUID testRunItemId);

    List<TestResult> findByTestRunItem_TestRun_Id(UUID testRunId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT DISTINCT tr
        FROM TestResult tr
        JOIN FETCH tr.testRunItem tri
        JOIN FETCH tri.testRun run
        JOIN FETCH tri.testCase tc
        LEFT JOIN FETCH tc.testCaseInput input
        WHERE run.id = :testRunId
          AND tr.resultStatus IN :statuses
    """)
    List<TestResult> findFailedResultsWithPayloadData(
        @org.springframework.data.repository.query.Param("testRunId") UUID testRunId,
        @org.springframework.data.repository.query.Param("statuses") Collection<com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus> statuses
    );

    @org.springframework.data.jpa.repository.Query("""
        SELECT DISTINCT tr
        FROM TestResult tr
        JOIN FETCH tr.testRunItem tri
        JOIN FETCH tri.testRun run
        JOIN FETCH tri.testCase tc
        LEFT JOIN FETCH tc.testCaseInput input
        WHERE run.id = :testRunId
          AND tr.resultStatus IN :statuses
          AND NOT EXISTS (
            SELECT 1 FROM TestFailureAnalysis tfa WHERE tfa.testResult.id = tr.id
          )
    """)
    List<TestResult> findUnanalyzedFailedResultsWithPayloadData(
        @org.springframework.data.repository.query.Param("testRunId") UUID testRunId,
        @org.springframework.data.repository.query.Param("statuses") Collection<com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus> statuses
    );

    // ── Permanent delete support ───────────────────────────────────────────────

    /**
     * Delete all TestResult rows for a project via chain:
     * TestResult → TestRunItem → TestRun → SourceProject
     */
    @Modifying
    @Query("""
        DELETE FROM TestResult tr
        WHERE tr.testRunItem.id IN (
            SELECT tri.id FROM TestRunItem tri
            WHERE tri.testRun.id IN (
                SELECT run.id FROM TestRun run WHERE run.sourceProject.id = :projectId
            )
        )
    """)
    void deleteByTestRunProjectId(@Param("projectId") UUID projectId);
}
