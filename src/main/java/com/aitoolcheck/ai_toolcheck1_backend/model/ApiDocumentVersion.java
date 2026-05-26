package com.aitoolcheck.ai_toolcheck1_backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "api_document_version")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiDocumentVersion {

    private static final Clock UTC_CLOCK = Clock.systemUTC();

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "version_no")
    private Integer versionNo;

    @Column(name = "summary")
    private String summary;

    @Column(name = "description")
    private String description;

    @Column(name = "openapi_fragment_json", columnDefinition = "TEXT")
    private String openapiFragmentJson;

    @Lob
    @Column(name = "content_json", columnDefinition = "LONGTEXT")
    private String contentJson;

    @Column(name = "ai_enriched_flag")
    private Boolean aiEnrichedFlag;

    /**
     * Stored in UTC.
     * API response layer converts this value to Asia/Ho_Chi_Minh offset time.
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * Stored in UTC.
     * API response layer converts this value to Asia/Ho_Chi_Minh offset time.
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_document_id", referencedColumnName = "id", nullable = false)
    private ApiDocument apiDocument;

    @OneToMany(mappedBy = "apiDocumentVersion", fetch = FetchType.LAZY)
    private List<TestCase> testCases;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now(UTC_CLOCK);

        this.createdAt = now;
        this.updatedAt = now;

        if (this.aiEnrichedFlag == null) {
            this.aiEnrichedFlag = false;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now(UTC_CLOCK);
    }
}