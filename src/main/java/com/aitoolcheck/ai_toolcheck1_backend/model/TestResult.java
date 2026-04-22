package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import jakarta.persistence.*;
import lombok.*;

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

    @Column(name = "result_status")
    @Enumerated(EnumType.STRING)
    private ResultStatus resultStatus;

    @Column(name = "response_time_ms")
    private Integer responseTimeMs;

    @Column(name = "actual_response_json", columnDefinition = "TEXT")
    private String actualResponseJson;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "blocked_reason")
    private String blockedReason;

    // ── Relationships ──────────────────────────────────────────────────────────

    // Owning side of the 1-1: holds the UNIQUE FK test_run_item_id
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_run_item_id", referencedColumnName = "id", unique = true, nullable = false)
    private TestRunItem testRunItem;

    @OneToMany(mappedBy = "testResult", fetch = FetchType.LAZY)
    private List<AiJobLog> aiJobLogs;
}
