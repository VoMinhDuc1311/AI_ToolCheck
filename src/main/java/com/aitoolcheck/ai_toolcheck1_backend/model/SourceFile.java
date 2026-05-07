package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.FileType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "source_file")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "package_name")
    private String packageName;

    @Column(name = "class_name")
    private String className;

    @Column(name = "file_type")
    @Enumerated(EnumType.STRING)
    private FileType fileType;

    @Column(name = "checksum_sha256")
    private String checksumSha256;

    @Lob
    @Column(name = "source_content", columnDefinition = "LONGTEXT")
    private String sourceContent;

    @Column(name = "parsed_flag")
    private Boolean parsedFlag;

    @Column(name = "parse_error", length = 1000)
    private String parseError;

    @Column(name = "active_flag", nullable = false)
    private Boolean activeFlag;

    @Column(name = "deleted_flag", nullable = false)
    private Boolean deletedFlag;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;



    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id", nullable = false)
    private SourceProject sourceProject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "upload_version_id", referencedColumnName = "id")
    private SourceUploadVersion uploadVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_seen_upload_version_id", referencedColumnName = "id")
    private SourceUploadVersion lastSeenUploadVersion;

    @OneToMany(mappedBy = "sourceFile", fetch = FetchType.LAZY)
    private List<ApiEndpoint> apiEndpoints;

    @OneToMany(mappedBy = "sourceFile", fetch = FetchType.LAZY)
    private List<LegacyInferenceLog> legacyInferenceLogs;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.activeFlag == null) {
            this.activeFlag = true;
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
