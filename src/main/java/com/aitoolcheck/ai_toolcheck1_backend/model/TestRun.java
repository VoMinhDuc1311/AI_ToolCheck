package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.EnvironmentType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RunStatus;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "test_run")
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

    @Column(name = "run_code")
    private String runCode;

    @Column(name = "environment_name")
    @Enumerated(EnumType.STRING)
    private EnvironmentType environmentName;

    @Column(name = "execution_mode")
    @Enumerated(EnumType.STRING)
    private ExecutionMode executionMode;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "run_status")
    @Enumerated(EnumType.STRING)
    private RunStatus runStatus;

    // ── Relationships ──────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id", nullable = false)
    private SourceProject sourceProject;

    // Join-table side: test_run <-> test_case through test_run_item
    @OneToMany(mappedBy = "testRun", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<TestRunItem> testRunItems;
}
