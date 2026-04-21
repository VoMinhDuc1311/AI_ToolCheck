package com.aitoolcheck.ai_toolcheck1_backend.feature.code_analyzer.entity;

import com.aitoolcheck.ai_toolcheck1_backend.enums.SourceStyle;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "source_analysis_result")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceAnalysisResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "source_style")
    @Enumerated(EnumType.STRING)
    private SourceStyle sourceStyle;

    @Column(name = "annotation_score")
    private Integer annotationScore;

    @Column(name = "structure_score")
    private Integer structureScore;

    @Column(name = "parser_recommended")
    private Boolean parserRecommended;

    @Column(name = "ai_recommended")
    private Boolean aiRecommended;

    // ── Relationships ──────────────────────────────────────────────────────────

    // Owning side of the 1-1: holds the FK column project_id (UNIQUE)
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id", unique = true, nullable = false)
    private SourceProject sourceProject;
}
