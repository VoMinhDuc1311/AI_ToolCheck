package com.aitoolcheck.ai_toolcheck1_backend.dto.sourceanalysisresult.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.SourceStyle;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceAnalysisResultDetailResponse {

    private UUID id;
    private UUID projectId;
    private SourceStyle sourceStyle;
    private Integer annotationScore;
    private Integer structureScore;
    private Boolean parserRecommended;
    private Boolean aiRecommended;
    private Integer totalFiles;
    private Integer analyzableFiles;
    private Integer parsedSuccessFiles;
    private Integer parsedFailedFiles;
    private Integer parsedFailed;
    private Double parseSuccessRate;
    private String summary;
    private Boolean currentFlag;
    private UUID sourceUploadVersionId;
    private UUID latestUploadVersionId;
    private Integer versionNo;
    private String originalFileName;
    private Integer totalJavaFilesFound;
    private Integer savedFiles;
    private Integer ignoredFiles;
    private Integer addedFiles;
    private Integer updatedFiles;
    private Integer unchangedFiles;
    private Integer deletedFiles;
    private String uploadStatus;
    private LocalDateTime uploadCreatedAt;
    private LocalDateTime completedAt;
}
