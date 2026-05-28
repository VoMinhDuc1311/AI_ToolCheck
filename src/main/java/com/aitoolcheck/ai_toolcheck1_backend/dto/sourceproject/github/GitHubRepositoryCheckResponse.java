package com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.github;

import com.aitoolcheck.ai_toolcheck1_backend.enums.GitHubDetectedProjectType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Response DTO returned by
 * {@code POST /v1/source-projects/{projectId}/github/check}.
 *
 * <p>All fields are safe to serialise — no raw tokens or credentials are
 * included.  The {@code repositoryUrl} is taken from the already-validated
 * value stored in {@code SourceProject}, never from the request body.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GitHubRepositoryCheckResponse {

    // ── Reachability ──────────────────────────────────────────────────────────

    /** {@code true} if the repository was reachable and the HEAD API call succeeded. */
    private boolean reachable;

    // ── Repository identity ───────────────────────────────────────────────────

    /** Always {@code "GITHUB"} for this endpoint. */
    private String provider;

    /** Owner / organisation extracted from the repository URL. */
    private String owner;

    /** Repository name extracted from the repository URL. */
    private String repositoryName;

    /** Normalised repository URL as stored on the project (no {@code .git}). */
    private String repositoryUrl;

    // ── Branch ────────────────────────────────────────────────────────────────

    /**
     * The branch that was actually probed.
     * Either the branch stored on the project, or the resolved default branch.
     */
    private String branch;

    /**
     * Default branch resolved from the GitHub repository (e.g. {@code main},
     * {@code master}).  {@code null} when the repository was not reachable.
     */
    private String defaultBranch;

    // ── Project type detection ────────────────────────────────────────────────

    /**
     * Best-effort detection of the build system / framework based on the
     * presence of well-known marker files.
     */
    private GitHubDetectedProjectType detectedProjectType;

    private boolean hasPomXml;
    private boolean hasBuildGradle;
    private boolean hasSettingsGradle;
    private boolean hasPackageJson;

    /** {@code true} if the path {@code src/main/java} exists in the repository tree. */
    private boolean hasSrcMainJava;

    // ── Human-readable summary ────────────────────────────────────────────────

    /** Short human-readable message describing the check outcome. */
    private String message;
}
