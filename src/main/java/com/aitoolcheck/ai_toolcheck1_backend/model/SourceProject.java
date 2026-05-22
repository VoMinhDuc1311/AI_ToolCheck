package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BackendType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectVisibility;
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

    @Column(name = "backend_type", length = 50)
    @Enumerated(EnumType.STRING)
    private BackendType backendType;

    @Column(name = "status", length = 50)
    @Enumerated(EnumType.STRING)
    private ProjectStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    private AppUser ownerUser;

    @Column(name = "visibility", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private ProjectVisibility visibility;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── Archive lifecycle ──────────────────────────────────────────────────────

    /**
     * true = project is archived; hidden from default active lists. Child data
     * preserved.
     */
    @Column(name = "archived_flag", nullable = false)
    private Boolean archivedFlag;

    /** Timestamp when the project was archived. Null when not archived. */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    /** UUID of the AppUser who archived the project (raw BINARY(16) reference). */
    @Column(name = "archived_by")
    private UUID archivedBy;

    // ── Permanent-delete tracking ──────────────────────────────────────────────

    /** true = permanent deletion in progress or completed. Used as guard flag. */
    @Column(name = "deleted_flag", nullable = false)
    private Boolean deletedFlag;

    /** Timestamp when permanent deletion was initiated. */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** UUID of the AppUser who permanently deleted the project. */
    @Column(name = "deleted_by")
    private UUID deletedBy;

    // ── Relationships ──────────────────────────────────────────────────────────

    @OneToOne(mappedBy = "sourceProject", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private SourceAnalysisResult sourceAnalysisResult;

    @OneToOne(mappedBy = "sourceProject", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private ApiDocument apiDocument;

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
        if (this.visibility == null) {
            this.visibility = ProjectVisibility.PRIVATE;
        }
        if (this.archivedFlag == null) {
            this.archivedFlag = false;
        }
        if (this.deletedFlag == null) {
            this.deletedFlag = false;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
