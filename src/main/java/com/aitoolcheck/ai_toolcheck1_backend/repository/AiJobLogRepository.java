package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;
import com.aitoolcheck.ai_toolcheck1_backend.repository.projection.AiJobStatisticProjection;
import com.aitoolcheck.ai_toolcheck1_backend.repository.projection.TokenUsageProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AiJobLogRepository extends JpaRepository<AiJobLog, UUID> {
    long countByExecutionStatus(ExecutionStatus status);

    @Query("SELECT COALESCE(SUM(a.tokenInput), 0L) FROM AiJobLog a")
    long sumTotalTokenInput();

    @Query("SELECT COALESCE(SUM(a.tokenOutput), 0L) FROM AiJobLog a")
    long sumTotalTokenOutput();

    @Query("""
        SELECT 
            COALESCE(SUM(a.tokenInput), 0) AS totalInputToken,
            COALESCE(SUM(a.tokenOutput), 0) AS totalOutputToken,
            COALESCE(SUM(a.tokenInput), 0) + COALESCE(SUM(a.tokenOutput), 0) AS totalToken
        FROM AiJobLog a
    """)
    TokenUsageProjection getTokenUsageStatistics();

    @Query("""
        SELECT 
            a.jobType AS jobType,
            a.executionStatus AS executionStatus,
            COUNT(a) AS totalJobs,
            COALESCE(SUM(a.tokenInput), 0) AS totalInputToken,
            COALESCE(SUM(a.tokenOutput), 0) AS totalOutputToken,
            COALESCE(SUM(a.tokenInput), 0) + COALESCE(SUM(a.tokenOutput), 0) AS totalToken
        FROM AiJobLog a
        GROUP BY a.jobType, a.executionStatus
    """)
    List<AiJobStatisticProjection> getAiJobStatistics();

    boolean existsBySourceProject_IdAndApiEndpoint_IdAndJobTypeAndExecutionStatusIn(
            UUID projectId,
            UUID apiEndpointId,
            JobType jobType,
            java.util.Collection<ExecutionStatus> statuses
    );

    java.util.Optional<AiJobLog> findTopBySourceProject_IdAndApiEndpoint_IdAndJobTypeOrderByStartedAtDesc(
            UUID projectId,
            UUID apiEndpointId,
            JobType jobType
    );
}
