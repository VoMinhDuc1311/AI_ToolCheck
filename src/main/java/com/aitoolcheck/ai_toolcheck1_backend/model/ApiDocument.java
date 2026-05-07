package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.DocumentType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "api_document")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "document_name")
    private String documentName;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type")
    private DocumentType documentType;

    @Column(name = "current_version_no")
    private Integer currentVersionNo;

    @Column(name = "published_flag")
    private Boolean publishedFlag;

    @Column(name = "stale_flag", nullable = false)
    private Boolean staleFlag;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id", unique = true, nullable = false)
    private SourceProject sourceProject;

    @OneToMany(mappedBy = "apiDocument", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<ApiDocumentVersion> apiDocumentVersions;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.currentVersionNo == null) {
            this.currentVersionNo = 0;
        }
        if (this.publishedFlag == null) {
            this.publishedFlag = false;
        }
        if (this.documentType == null) {
            this.documentType = DocumentType.OPENAPI_3;
        }
        if (this.staleFlag == null) {
            this.staleFlag = false;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
