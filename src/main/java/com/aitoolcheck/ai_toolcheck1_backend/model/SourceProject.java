package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BackendType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "source_project")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceProject {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "project_key")
    private String projectKey;

    @Column(name = "project_name")
    private String projectName;

    @Column(name = "description")
    private String description;

    @Column(name = "backend_type")
    @Enumerated(EnumType.STRING)
    private BackendType backendType;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private ProjectStatus status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToOne(mappedBy = "sourceProject", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private SourceAnalysisResult sourceAnalysisResult;

    @OneToMany(mappedBy = "sourceProject", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<SourceFile> sourceFiles;

    @OneToMany(mappedBy = "sourceProject", fetch = FetchType.LAZY)
    private List<ApiEndpoint> apiEndpoints;

    @OneToMany(mappedBy = "sourceProject", fetch = FetchType.LAZY)
    private List<ApiSchema> apiSchemas;

    @OneToMany(mappedBy = "sourceProject", fetch = FetchType.LAZY)
    private List<TestCase> testCases;

    @OneToMany(mappedBy = "sourceProject", fetch = FetchType.LAZY)
    private List<TestRun> testRuns;

    @OneToMany(mappedBy = "sourceProject", fetch = FetchType.LAZY)
    private List<AiJobLog> aiJobLogs;

    @OneToMany(mappedBy = "sourceProject", fetch = FetchType.LAZY)
    private List<LegacyInferenceLog> legacyInferenceLogs;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}