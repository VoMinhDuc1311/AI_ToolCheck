package com.aitoolcheck.ai_toolcheck1_backend.service.analysis;

import com.aitoolcheck.ai_toolcheck1_backend.enums.SourceStyle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SourceAnalysisDecisionService {

    private final SourceAnalysisScoringService scoringService;

    // Priority order:
    // 1. Strong modern signals (@RestController + composed mapping) → MODERN
    // 2. Strong legacy signals (@RequestMapping(method=...) or @Controller+@ResponseBody) → LEGACY
    // 3. Score gate (parseSuccessRate >= 0.70, annotationScore >= 50, structureScore >= 50) → MODERN
    // 4. Default → LEGACY
    public SourceStyle determineSourceStyle(
            AnalysisSignals signals,
            double parseSuccessRate,
            int annotationScore,
            int structureScore
    ) {
        if (signals.hasStrongModernSpringSignals()) {
            return SourceStyle.MODERN;
        }
        if (signals.hasStrongLegacySignals()) {
            return SourceStyle.LEGACY;
        }
        if (parseSuccessRate >= 0.70 && annotationScore >= 50 && structureScore >= 50) {
            return SourceStyle.MODERN;
        }
        return SourceStyle.LEGACY;
    }

    public boolean determineParserRecommended(double parseSuccessRate, int annotationScore, int structureScore) {
        return parseSuccessRate >= 0.70 && annotationScore >= 50 && structureScore >= 50;
    }

    public boolean determineAiRecommended(
            SourceStyle sourceStyle,
            double parseSuccessRate,
            int annotationScore,
            int structureScore,
            int parsedFailedFiles,
            int analyzableFiles
    ) {
        double parseFailureRate = scoringService.calculateRate(parsedFailedFiles, analyzableFiles);
        return parseSuccessRate < 0.70
                || annotationScore < 40
                || structureScore < 40
                || sourceStyle == SourceStyle.LEGACY
                || (parsedFailedFiles > 0 && parseFailureRate >= 0.20);
    }
}
