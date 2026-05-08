package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.SourceUploadStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "source_upload_version",
        indexes = {
                @Index(name = "idx_source_upload_version_project", columnList = "project_id"),
                @Index(name = "uk_source_upload_version_project_no", columnList = "project_id,version_no", unique = true)
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceUploadVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id", nullable = false)
    private SourceProject sourceProject;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "total_java_files_found")
    private Integer totalJavaFilesFound;

    @Column(name = "saved_files")
    private Integer savedFiles;

    @Column(name = "ignored_files")
    private Integer ignoredFiles;

    @Column(name = "added_files")
    private Integer addedFiles;

    @Column(name = "updated_files")
    private Integer updatedFiles;

    @Column(name = "unchanged_files")
    private Integer unchangedFiles;

    @Column(name = "deleted_files")
    private Integer deletedFiles;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SourceUploadStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @OneToMany(mappedBy = "uploadVersion", fetch = FetchType.LAZY)
    private List<SourceFile> uploadedFiles;

    @OneToMany(mappedBy = "lastSeenUploadVersion", fetch = FetchType.LAZY)
    private List<SourceFile> lastSeenFiles;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = SourceUploadStatus.PROCESSING;
        }
    }
}
