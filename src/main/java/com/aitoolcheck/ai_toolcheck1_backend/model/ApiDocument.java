package com.aitoolcheck.ai_toolcheck1_backend.model;

import jakarta.persistence.*;
import lombok.*;

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

    @Column(name = "current_version_no")
    private Integer currentVersionNo;

    @Column(name = "published_flag")
    private Boolean publishedFlag;

    // ── Relationships ──────────────────────────────────────────────────────────

    // Owning side of the 1-1: holds the UNIQUE FK api_endpoint_id
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_endpoint_id", referencedColumnName = "id", unique = true, nullable = false)
    private ApiEndpoint apiEndpoint;

    @OneToMany(mappedBy = "apiDocument", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<ApiDocumentVersion> apiDocumentVersions;
}
