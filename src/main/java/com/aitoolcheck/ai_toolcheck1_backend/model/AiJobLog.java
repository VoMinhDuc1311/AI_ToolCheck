package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ai_job_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiJobLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "job_type", length = 50)
    @Enumerated(EnumType.STRING)
    private JobType jobType;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "ai_model_used", length = 100)
    private String aiModelUsed;

    @Column(name = "token_input")
    private Integer tokenInput;

    @Column(name = "token_output")
    private Integer tokenOutput;

    @Column(name = "execution_status", length = 50)
    @Enumerated(EnumType.STRING)
    private ExecutionStatus executionStatus;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;


    @JsonIgnore  // Tránh LazyInitializationException khi Jackson serialize response
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id", nullable = false)
    private SourceProject sourceProject;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_endpoint_id", referencedColumnName = "id")
    private ApiEndpoint apiEndpoint;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_result_id", referencedColumnName = "id")
    private TestResult testResult;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_skill_id", referencedColumnName = "id")
    private AiSkill aiSkill;

    /**
     * Groups all AI jobs created in a single "generate-docs-from-source" call.
     * Nullable — legacy jobs created before this field was added will have NULL.
     * FE uses this to filter/monitor jobs belonging to the same scan batch.
     */
    @Column(name = "scan_batch_id")
    private UUID scanBatchId;
}
