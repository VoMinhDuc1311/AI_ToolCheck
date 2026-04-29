package com.aitoolcheck.ai_toolcheck1_backend.model;

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

    @Column(name = "document_type")
    private String documentType;

    @Column(name = "current_version_no")
    private Integer currentVersionNo;

    @Column(name = "published_flag")
    private Boolean publishedFlag;

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
            this.documentType = "OPENAPI_3";
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
