package com.aitoolcheck.ai_toolcheck1_backend.dto.sourceanalysisresult.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.SourceStyle;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
    private Double parseSuccessRate;
    private String summary;
    private Boolean currentFlag;
    private UUID sourceUploadVersionId;
}
