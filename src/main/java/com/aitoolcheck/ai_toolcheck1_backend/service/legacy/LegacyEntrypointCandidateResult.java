package com.aitoolcheck.ai_toolcheck1_backend.service.legacy;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

/**
 * Result of classifying a single SourceFile as a legacy API entrypoint candidate or not.
 *
 * <p>Candidate=true means this file should have an AI inference job created for it.
 * Candidate=false means it is a helper/utility/non-entrypoint and should be skipped.</p>
 */
@Getter
@Builder
public class LegacyEntrypointCandidateResult {

    private UUID sourceFileId;
    private String fileName;
    private String filePath;
    /** True if this file should receive an AI inference job. */
    private boolean candidate;
    /** High-level type label: SERVLET, STRUTS_ACTION, CONTROLLER_LIKE, ROUTER, HELPER, UNKNOWN. */
    private String candidateType;
    /** Numeric score — higher = more confident it is an entrypoint. */
    private int score;
    /** Human-readable reasons that contributed to the classification decision. */
    private List<String> reasons;
}
