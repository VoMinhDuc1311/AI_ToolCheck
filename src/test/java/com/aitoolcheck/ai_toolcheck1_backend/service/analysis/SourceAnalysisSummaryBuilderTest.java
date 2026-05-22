package com.aitoolcheck.ai_toolcheck1_backend.service.analysis;

import com.aitoolcheck.ai_toolcheck1_backend.enums.SourceStyle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceAnalysisSummaryBuilderTest {

    private SourceAnalysisSummaryBuilder summaryBuilder;

    @BeforeEach
    void setUp() {
        summaryBuilder = new SourceAnalysisSummaryBuilder();
    }

    @Test
    void modern_parserRecommended_aiNotRecommended_returnsCleanModernMessage() {
        String summary = summaryBuilder.buildSummary(SourceStyle.MODERN, true, false);
        assertThat(summary).contains("Modern Spring project");
        assertThat(summary).doesNotContain("Legacy");
        assertThat(summary).doesNotContain("AI fallback");
    }

    @Test
    void modern_parserNotRecommended_aiRecommended_returnsMixedConfidenceMessage() {
        String summary = summaryBuilder.buildSummary(SourceStyle.MODERN, false, true);
        assertThat(summary).contains("Modern Spring project");
        assertThat(summary).doesNotContain("Legacy or weakly structured");
        assertThat(summary).contains("mixed parse confidence");
    }

    @Test
    void modern_parserRecommended_aiAlsoRecommended_returnsMixedConfidenceMessage() {
        String summary = summaryBuilder.buildSummary(SourceStyle.MODERN, true, true);
        assertThat(summary).contains("Modern Spring project");
        assertThat(summary).doesNotContain("Legacy or weakly structured");
        assertThat(summary).contains("mixed parse confidence");
    }

    @Test
    void legacy_returnsLegacyMessage() {
        String summary = summaryBuilder.buildSummary(SourceStyle.LEGACY, false, true);
        assertThat(summary).contains("Legacy or weakly structured project");
    }

    @Test
    void legacy_summaryNeverSaysModern() {
        String summary = summaryBuilder.buildSummary(SourceStyle.LEGACY, false, true);
        assertThat(summary).doesNotContain("Modern Spring project");
    }
}
