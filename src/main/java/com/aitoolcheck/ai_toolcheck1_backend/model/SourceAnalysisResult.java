package com.aitoolcheck.ai_toolcheck1_backend.model;

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

    @Column(name = "total_files")
    private Integer totalFiles;

    @Column(name = "analyzable_files")
    private Integer analyzableFiles;

    @Column(name = "parsed_success_files")
    private Integer parsedSuccessFiles;

    @Column(name = "parsed_failed_files")
    private Integer parsedFailedFiles;

    @Column(name = "parse_success_rate")
    private Double parseSuccessRate;

    @Column(name = "summary", length = 1000)
    private String summary;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id", unique = true, nullable = false)
    private SourceProject sourceProject;
}
