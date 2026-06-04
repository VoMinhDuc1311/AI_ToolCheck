package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BatchRunItemStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BatchRunStep;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "batch_run_item",
        indexes = {
                @Index(name = "idx_batch_run_item_batch_run_id", columnList = "batch_run_id"),
                @Index(name = "idx_batch_run_item_project_id", columnList = "project_id"),
                @Index(name = "idx_batch_run_item_status", columnList = "status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchRunItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_run_id", referencedColumnName = "id", nullable = false)
    private BatchRun batchRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id", nullable = false)
    private SourceProject sourceProject;

    @Column(name = "current_step", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private BatchRunStep currentStep;

    @Column(name = "status", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private BatchRunItemStatus status;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "max_retries", nullable = false)
    private Integer maxRetries;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_document_version_id", referencedColumnName = "id")
    private ApiDocumentVersion apiDocumentVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_run_id", referencedColumnName = "id")
    private TestRun testRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "runtime_id", referencedColumnName = "id")
    private SourceRuntime runtime;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (currentStep == null) currentStep = BatchRunStep.GENERATE_OPENAPI;
        if (status == null) status = BatchRunItemStatus.PENDING;
        if (retryCount == null) retryCount = 0;
        if (maxRetries == null) maxRetries = 1;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
