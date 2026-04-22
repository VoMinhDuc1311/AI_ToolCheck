package com.aitoolcheck.ai_toolcheck1_backend.dto.sourceanalysisresult.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.SourceStyle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateSourceAnalysisResultRequest {

    private SourceStyle sourceStyle;
    private Integer annotationScore;
    private Integer structureScore;
    private Boolean parserRecommended;
    private Boolean aiRecommended;
}