package com.aitoolcheck.ai_toolcheck1_backend.model;

import jakarta.persistence.*;
import lombok.*;

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

    @Column(name = "example_request_json", columnDefinition = "TEXT")
    private String exampleRequestJson;

    @Column(name = "example_response_json", columnDefinition = "TEXT")
    private String exampleResponseJson;

    @Column(name = "openapi_fragment_json", columnDefinition = "TEXT")
    private String openapiFragmentJson;

    @Column(name = "ai_enriched_flag")
    private Boolean aiEnrichedFlag;

    // ── Relationships ──────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_document_id", referencedColumnName = "id", nullable = false)
    private ApiDocument apiDocument;

    @OneToMany(mappedBy = "apiDocumentVersion", fetch = FetchType.LAZY)
    private List<TestCase> testCases;
}
