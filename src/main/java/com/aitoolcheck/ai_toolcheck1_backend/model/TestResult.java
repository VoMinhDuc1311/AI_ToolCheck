package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "test_result")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "actual_status")
    private Integer actualStatus;

    @Column(name = "result_status", length = 20)
    @Enumerated(EnumType.STRING)
    private ResultStatus resultStatus;

    @Column(name = "response_time_ms")
    private Integer responseTimeMs;

    @Column(name = "actual_response_json", columnDefinition = "TEXT")
    private String actualResponseJson;

    /**
     * HTTP response headers captured during execution, stored as a JSON object string.
     * Example: {"Content-Type":"application/json","X-Request-Id":"abc123"}
     * Null when no response was received (network error) or on legacy records.
     * Used by {@link com.aitoolcheck.ai_toolcheck1_backend.service.RuleEngineService}
     * for HEADER assertion evaluation (case-insensitive key lookup).
     */
    @Column(name = "actual_response_headers_json", columnDefinition = "TEXT")
    private String actualResponseHeadersJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "blocked_reason", columnDefinition = "TEXT")
    private String blockedReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_run_item_id", referencedColumnName = "id", unique = true, nullable = false)
    private TestRunItem testRunItem;

    @OneToMany(mappedBy = "testResult", fetch = FetchType.LAZY)
    private List<AiJobLog> aiJobLogs;

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
