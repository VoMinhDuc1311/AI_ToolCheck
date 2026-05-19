package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.TestFailureAnalysis;
import com.aitoolcheck.ai_toolcheck1_backend.repository.projection.FailureAnalysisTypeProjection;
import com.aitoolcheck.ai_toolcheck1_backend.repository.projection.FailurePriorityProjection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

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

    List<TestFailureAnalysis> findByTestResult_IdOrderByCreatedAtDesc(UUID testResultId);

    Optional<TestFailureAnalysis> findFirstByTestResult_IdOrderByCreatedAtDesc(UUID testResultId);

    boolean existsByTestResult_Id(UUID testResultId);

    Optional<TestFailureAnalysis> findByAiJobLog_Id(UUID aiJobLogId);
}
