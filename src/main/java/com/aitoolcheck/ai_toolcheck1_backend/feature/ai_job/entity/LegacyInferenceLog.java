package com.aitoolcheck.ai_toolcheck1_backend.feature.ai_job.entity;

import com.aitoolcheck.ai_toolcheck1_backend.feature.api_document.entity.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.feature.code_analyzer.entity.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.feature.code_analyzer.entity.SourceProject;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "legacy_inference_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LegacyInferenceLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "inferred_metadata_json", columnDefinition = "TEXT")
    private String inferredMetadataJson;

    @Column(name = "confidence_score")
    private Integer confidenceScore;

    // ── Relationships ──────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id", nullable = false)
    private SourceProject sourceProject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_file_id", referencedColumnName = "id")
    private SourceFile sourceFile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_endpoint_id", referencedColumnName = "id")
    private ApiEndpoint apiEndpoint;
}
