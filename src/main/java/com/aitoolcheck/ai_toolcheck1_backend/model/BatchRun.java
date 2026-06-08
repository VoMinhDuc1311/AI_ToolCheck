package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BatchRunStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BuildStrategy;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "batch_run",
        indexes = {
                @Index(name = "idx_batch_run_status", columnList = "status"),
                @Index(name = "idx_batch_run_created_at", columnList = "created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "status", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private BatchRunStatus status;

    @Column(name = "total_items", nullable = false)
    private Integer totalItems;

    @Column(name = "success_count", nullable = false)
    private Integer successCount;

    @Column(name = "failed_count", nullable = false)
    private Integer failedCount;

    @Column(name = "skipped_count", nullable = false)
    private Integer skippedCount;

    @Column(name = "running_count", nullable = false)
    private Integer runningCount;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "generate_open_api", nullable = false)
    private Boolean generateOpenApi;

    @Column(name = "generate_test_cases", nullable = false)
    private Boolean generateTestCases;

    @Column(name = "start_runtime", nullable = false)
    private Boolean startRuntime;

    @Column(name = "execute_test_run", nullable = false)
    private Boolean executeTestRun;

    @Column(name = "stop_runtime_after_run", nullable = false)
    private Boolean stopRuntimeAfterRun;

    @Column(name = "max_concurrency", nullable = false)
    private Integer maxConcurrency;

    @Column(name = "max_retries", nullable = false)
    private Integer maxRetries;

    @Column(name = "execution_mode", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionMode executionMode;

    @Column(name = "build_strategy", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private BuildStrategy buildStrategy;

    @Column(name = "external_base_url", length = 500)
    private String externalBaseUrl;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "batchRun", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BatchRunItem> items;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) status = BatchRunStatus.PENDING;
        if (totalItems == null) totalItems = 0;
        if (successCount == null) successCount = 0;
        if (failedCount == null) failedCount = 0;
        if (skippedCount == null) skippedCount = 0;
        if (runningCount == null) runningCount = 0;
        if (generateOpenApi == null) generateOpenApi = true;
        if (generateTestCases == null) generateTestCases = true;
        if (startRuntime == null) startRuntime = true;
        if (executeTestRun == null) executeTestRun = true;
        if (stopRuntimeAfterRun == null) stopRuntimeAfterRun = false;
        if (maxConcurrency == null) maxConcurrency = 1;
        if (maxRetries == null) maxRetries = 1;
        if (executionMode == null) executionMode = com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionMode.READ_ONLY;
        if (buildStrategy == null) buildStrategy = BuildStrategy.AUTO;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
