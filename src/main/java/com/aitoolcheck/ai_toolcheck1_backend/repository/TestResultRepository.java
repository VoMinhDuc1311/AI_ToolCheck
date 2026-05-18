package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.TestResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TestResultRepository extends JpaRepository<TestResult, UUID> {

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
        @org.springframework.data.repository.query.Param("statuses") java.util.Collection<com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus> statuses
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
        @org.springframework.data.repository.query.Param("statuses") java.util.Collection<com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus> statuses
    );
}
