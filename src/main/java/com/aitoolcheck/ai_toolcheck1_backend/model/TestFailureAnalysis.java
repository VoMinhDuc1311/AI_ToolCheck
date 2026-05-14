package com.aitoolcheck.ai_toolcheck1_backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "test_failure_analysis")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestFailureAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_result_id", referencedColumnName = "id", nullable = false)
    private TestResult testResult;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_job_log_id", referencedColumnName = "id")
    private AiJobLog aiJobLog;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "failure_type", length = 100)
    private String failureType;

    @Column(name = "summary", length = 1000)
    private String summary;

    @Column(name = "root_cause", columnDefinition = "TEXT")
    private String rootCause;

    @Column(name = "expected_behavior", columnDefinition = "TEXT")
    private String expectedBehavior;

    @Column(name = "actual_behavior", columnDefinition = "TEXT")
    private String actualBehavior;

    @Column(name = "is_likely_backend_bug")
    private Boolean isLikelyBackendBug;

    @Column(name = "is_likely_test_case_bug")
    private Boolean isLikelyTestCaseBug;

    @Column(name = "suggested_fixes_json", columnDefinition = "TEXT")
    private String suggestedFixesJson;

    @Column(name = "recommended_next_action", columnDefinition = "TEXT")
    private String recommendedNextAction;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "priority", length = 50)
    private String priority;

    @Column(name = "raw_ai_response", columnDefinition = "LONGTEXT")
    private String rawAiResponse;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
