package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.TestFailureAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TestFailureAnalysisRepository extends JpaRepository<TestFailureAnalysis, UUID> {

    List<TestFailureAnalysis> findByTestResult_IdOrderByCreatedAtDesc(UUID testResultId);

    Optional<TestFailureAnalysis> findFirstByTestResult_IdOrderByCreatedAtDesc(UUID testResultId);

    boolean existsByTestResult_Id(UUID testResultId);

    Optional<TestFailureAnalysis> findByAiJobLog_Id(UUID aiJobLogId);
}
