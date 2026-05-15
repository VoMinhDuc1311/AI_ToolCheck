package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;
import org.springframework.data.jpa.repository.Query;

@Repository
public interface AiJobLogRepository extends JpaRepository<AiJobLog, UUID> {
    long countByExecutionStatus(ExecutionStatus status);

    @Query("SELECT COALESCE(SUM(a.tokenInput), 0L) FROM AiJobLog a")
    long sumTotalTokenInput();

    @Query("SELECT COALESCE(SUM(a.tokenOutput), 0L) FROM AiJobLog a")
    long sumTotalTokenOutput();

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
