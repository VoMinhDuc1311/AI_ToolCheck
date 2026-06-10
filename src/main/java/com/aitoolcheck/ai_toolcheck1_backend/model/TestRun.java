package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.EnvironmentType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RunStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "test_run",
        indexes = {
                @Index(name = "idx_test_run_project_id", columnList = "project_id"),
                @Index(name = "idx_test_run_run_status", columnList = "run_status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "run_code", length = 100)
    private String runCode;

    @Column(name = "run_name", nullable = false, length = 150)
    private String runName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @Column(name = "environment_name", length = 50)
    @Enumerated(EnumType.STRING)
    private EnvironmentType environmentName;

    @Column(name = "execution_mode", length = 50)
    @Enumerated(EnumType.STRING)
    private ExecutionMode executionMode;

    @Column(name = "run_status", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private RunStatus runStatus;

    @Column(name = "runtime_mode", length = 50)
    @Enumerated(EnumType.STRING)
    private RuntimeMode runtimeMode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_runtime_id", referencedColumnName = "id")
    private SourceRuntime sourceRuntime;

    @Column(name = "target_base_url_used", length = 500)
    private String targetBaseUrlUsed;

    @Column(name = "runtime_status_at_start", length = 50)
    private String runtimeStatusAtStart;

    @Column(name = "preflight_status", length = 50)
    private String preflightStatus;

    @Column(name = "preflight_summary", columnDefinition = "TEXT")
    private String preflightSummary;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id", nullable = false)
    private SourceProject sourceProject;

    @OneToMany(mappedBy = "testRun", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TestRunItem> testRunItems;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;

        if (runStatus == null) {
            runStatus = RunStatus.PENDING;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
