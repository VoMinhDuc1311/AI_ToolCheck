package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.TestFailureAnalysis;
import com.aitoolcheck.ai_toolcheck1_backend.repository.projection.FailureAnalysisTypeProjection;
import com.aitoolcheck.ai_toolcheck1_backend.repository.projection.FailurePriorityProjection;
import org.springframework.data.domain.Pageable;
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
public interface TestFailureAnalysisRepository extends JpaRepository<TestFailureAnalysis, UUID> {

    @Query("SELECT tfa.failureType AS failureType, COUNT(tfa) AS total FROM TestFailureAnalysis tfa GROUP BY tfa.failureType")
    List<FailureAnalysisTypeProjection> getFailureTypeStatistics();

    @Query("SELECT tfa.priority AS priority, COUNT(tfa) AS total FROM TestFailureAnalysis tfa GROUP BY tfa.priority")
    List<FailurePriorityProjection> getPriorityStatistics();

    @Query("SELECT tfa FROM TestFailureAnalysis tfa ORDER BY tfa.createdAt DESC")
    List<TestFailureAnalysis> findRecentAnalyses(Pageable pageable);

    @Query("""
        SELECT COUNT(tfa) FROM TestFailureAnalysis tfa
        WHERE tfa.testResult.testRunItem.testRun.sourceProject.id IN :projectIds
    """)
    long countByProjectIds(@Param("projectIds") Collection<UUID> projectIds);

    @Query("""
        SELECT tfa.failureType AS failureType, COUNT(tfa) AS total 
        FROM TestFailureAnalysis tfa 
        WHERE tfa.testResult.testRunItem.testRun.sourceProject.id IN :projectIds
        GROUP BY tfa.failureType
    """)
    List<FailureAnalysisTypeProjection> getFailureTypeStatisticsByProjectIds(@Param("projectIds") Collection<UUID> projectIds);

    @Query("""
        SELECT tfa.priority AS priority, COUNT(tfa) AS total 
        FROM TestFailureAnalysis tfa 
        WHERE tfa.testResult.testRunItem.testRun.sourceProject.id IN :projectIds
        GROUP BY tfa.priority
    """)
    List<FailurePriorityProjection> getPriorityStatisticsByProjectIds(@Param("projectIds") Collection<UUID> projectIds);

    @Query("""
        SELECT tfa FROM TestFailureAnalysis tfa 
        WHERE tfa.testResult.testRunItem.testRun.sourceProject.id IN :projectIds
        ORDER BY tfa.createdAt DESC
    """)
    List<TestFailureAnalysis> findRecentAnalysesByProjectIds(@Param("projectIds") Collection<UUID> projectIds, Pageable pageable);

    List<TestFailureAnalysis> findByTestResult_IdOrderByCreatedAtDesc(UUID testResultId);

    Optional<TestFailureAnalysis> findFirstByTestResult_IdOrderByCreatedAtDesc(UUID testResultId);

    boolean existsByTestResult_Id(UUID testResultId);

    Optional<TestFailureAnalysis> findByAiJobLog_Id(UUID aiJobLogId);

    // ── Permanent delete support ───────────────────────────────────────────────

    /**
     * Bulk delete all TestFailureAnalysis rows for a project via the chain:
     * TestFailureAnalysis → TestResult → TestRunItem → TestRun → SourceProject
     */
    @Modifying
    @Query("""
                DELETE FROM TestFailureAnalysis tfa
                WHERE tfa.testResult.id IN (
                    SELECT tr.id FROM TestResult tr
                    WHERE tr.testRunItem.id IN (
                        SELECT tri.id FROM TestRunItem tri
                        WHERE tri.testRun.id IN (
                            SELECT run.id FROM TestRun run WHERE run.sourceProject.id = :projectId
                        )
                    )
                )
            """)
    void deleteByTestRunProjectId(@Param("projectId") UUID projectId);
}
