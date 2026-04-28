package com.aitoolcheck.ai_toolcheck1_backend.service.analysis;

import com.aitoolcheck.ai_toolcheck1_backend.enums.SourceStyle;
import org.springframework.stereotype.Service;

@Service
public class SourceAnalysisSummaryBuilder {

    public String buildSummary(SourceStyle sourceStyle, boolean parserRecommended, boolean aiRecommended) {
        if (sourceStyle == SourceStyle.MODERN && parserRecommended && !aiRecommended) {
            return "Modern Spring project. Parser recommended because annotation and structure scores are high.";
        }
        if (sourceStyle == SourceStyle.MODERN && parserRecommended) {
            return "Modern Spring project with mixed parse confidence. Parser can be used, but AI fallback is also recommended.";
        }
        return "Legacy or weakly structured project. AI fallback recommended because parse success rate or scores are low.";
    }
}
