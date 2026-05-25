package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;
import com.aitoolcheck.ai_toolcheck1_backend.repository.projection.AiJobStatisticProjection;
import com.aitoolcheck.ai_toolcheck1_backend.repository.projection.TokenUsageProjection;
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

    @Query("SELECT COUNT(a) FROM AiJobLog a WHERE a.sourceProject.id IN :projectIds")
    long countByProjectIds(@Param("projectIds") Collection<UUID> projectIds);

    @Query("""
        SELECT
            COALESCE(SUM(a.tokenInput), 0) AS totalInputToken,
            COALESCE(SUM(a.tokenOutput), 0) AS totalOutputToken,
            COALESCE(SUM(a.tokenInput), 0) + COALESCE(SUM(a.tokenOutput), 0) AS totalToken
        FROM AiJobLog a
        WHERE a.sourceProject.id IN :projectIds
    """)
    TokenUsageProjection getTokenUsageStatisticsByProjectIds(@Param("projectIds") Collection<UUID> projectIds);

    @Query("""
        SELECT
            a.jobType AS jobType,
            a.executionStatus AS executionStatus,
            COUNT(a) AS totalJobs,
            COALESCE(SUM(a.tokenInput), 0) AS totalInputToken,
            COALESCE(SUM(a.tokenOutput), 0) AS totalOutputToken,
            COALESCE(SUM(a.tokenInput), 0) + COALESCE(SUM(a.tokenOutput), 0) AS totalToken
        FROM AiJobLog a
        WHERE a.sourceProject.id IN :projectIds
        GROUP BY a.jobType, a.executionStatus
    """)
    List<AiJobStatisticProjection> getAiJobStatisticsByProjectIds(@Param("projectIds") Collection<UUID> projectIds);

    boolean existsBySourceProject_IdAndApiEndpoint_IdAndJobTypeAndExecutionStatusIn(
            UUID projectId,
            UUID apiEndpointId,
            JobType jobType,
            Collection<ExecutionStatus> statuses);

    Optional<AiJobLog> findTopBySourceProject_IdAndApiEndpoint_IdAndJobTypeOrderByStartedAtDesc(
            UUID projectId,
            UUID apiEndpointId,
            JobType jobType);

    /**
     * Duplicate guard for legacy_code_reader: kiểm tra đã có job nào cho cùng
     * sourceFile + project + jobType ở trạng thái PENDING/RUNNING/SUCCESS chưa.
     * Dùng trong SourceDocumentationOrchestratorService để tránh tạo job trùng.
     *
     * <p>Lưu ý: AiJobLog không có FK trực tiếp đến SourceFile — field được thêm
     * thông qua query JPQL trên source_file_id nếu entity có field đó.
     * Hiện tại AiJobLog không có source_file_id column → dùng heuristic qua sourceProject+jobType.</p>
     */
    boolean existsBySourceProject_IdAndJobTypeAndExecutionStatusIn(
            UUID projectId,
            JobType jobType,
            Collection<ExecutionStatus> statuses);

    List<AiJobLog> findBySourceProject_IdOrderByStartedAtDesc(UUID projectId);

    List<AiJobLog> findAllByOrderByStartedAtDesc();

    List<AiJobLog> findBySourceProject_IdInOrderByStartedAtDesc(Collection<UUID> projectIds);

    // ── Permanent delete support ───────────────────────────────────────────────

    /**
     * Delete all AiJobLog rows for a project (project_id is NOT NULL in
     * ai_job_log).
     */
    @Modifying
    @Query("DELETE FROM AiJobLog a WHERE a.sourceProject.id = :projectId")
    void deleteBySourceProjectId(@Param("projectId") UUID projectId);
}

