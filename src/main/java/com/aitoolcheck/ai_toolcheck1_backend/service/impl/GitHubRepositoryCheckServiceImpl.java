package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.common.GitHubRepositoryUrlParser;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.github.GitHubRepositoryCheckResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.GitHubDetectedProjectType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.service.GitHubRepositoryCheckService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.UUID;

/**
 * Implements {@link GitHubRepositoryCheckService} using the public GitHub
 * REST API v3 (unauthenticated, rate-limit: 60 req/hour per IP).
 *
 * <p>Security guardrails:
 * <ul>
 *   <li>Only {@code https://api.github.com} is contacted — never
 *       user-supplied URLs.
 *   <li>No credentials or tokens are used.
 *   <li>Connect and read timeouts are enforced.
 *   <li>HTTP redirects are followed once only, and only to
 *       {@code https://api.github.com}.
 *   <li>A 404 / 403 from GitHub is treated as a clean business result
 *       ({@code reachable=false}) — never as an internal server error.
 *   <li>Sensitive URL parameters are never logged.
 * </ul>
 */
@Slf4j
@Service
public class GitHubRepositoryCheckServiceImpl implements GitHubRepositoryCheckService {

    // ── GitHub API base ───────────────────────────────────────────────────────

    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final String ACCEPT_HEADER   = "application/vnd.github+json";
    private static final String USER_AGENT      = "AI-ToolCheck-Backend/1.0";

    // ── HTTP timeouts (configurable via application.yaml) ─────────────────────

    @Value("${github.check.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${github.check.read-timeout-ms:8000}")
    private int readTimeoutMs;

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final ProjectAccessService projectAccessService;
    private final ObjectMapper         objectMapper;

    public GitHubRepositoryCheckServiceImpl(ProjectAccessService projectAccessService,
                                            ObjectMapper objectMapper) {
        this.projectAccessService = projectAccessService;
        this.objectMapper         = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @Override
    public GitHubRepositoryCheckResponse check(UUID projectId) {
        // 1. Load project + enforce view permission (uses existing RBAC)
        SourceProject project = projectAccessService.requireCanViewProject(projectId);

        // 2. Validate that a repository URL is configured
        String repoUrl = project.getRepositoryUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new BadRequestException(
                    "This project does not have a linked GitHub repository URL. " +
                    "Set repositoryUrl first via PUT /v1/source-projects/" + projectId + ".");
        }

        // 3. Extract owner / repo from the already-normalised stored URL
        String owner    = GitHubRepositoryUrlParser.extractOwner(repoUrl);
        String repoName = GitHubRepositoryUrlParser.extractRepo(repoUrl);

        if (owner == null || repoName == null) {
            // Should never happen if the URL was normalised on save, but guard anyway
            throw new BadRequestException(
                    "Stored repositoryUrl is in an unexpected format. Please update the project with a valid GitHub URL.");
        }

        // 4. Call GitHub API — all errors are handled as business results, not exceptions
        return performCheck(owner, repoName, project.getRepositoryBranch(), repoUrl);
    }

    // ── Internal check logic ──────────────────────────────────────────────────

    /**
     * Contacts GitHub API, resolves the default branch, probes the target
     * branch, and detects project type from marker files.
     *
     * <p>Any I/O error or unexpected HTTP status results in
     * {@code reachable=false} with a descriptive message — never a 500.
     */
    GitHubRepositoryCheckResponse performCheck(String owner,
                                               String repoName,
                                               String storedBranch,
                                               String repoUrl) {
        String safeRef = "[" + owner + "/" + repoName + "]"; // safe to log

        // ── Step A: Fetch repository metadata to get default branch ───────────
        RepoMeta meta = fetchRepoMeta(owner, repoName);
        if (!meta.reachable) {
            log.info("[GitHubCheck] Repository {} not reachable: {}", safeRef, meta.message);
            return GitHubRepositoryCheckResponse.builder()
                    .reachable(false)
                    .provider("GITHUB")
                    .owner(owner)
                    .repositoryName(repoName)
                    .repositoryUrl(repoUrl)
                    .branch(storedBranch)
                    .defaultBranch(null)
                    .detectedProjectType(GitHubDetectedProjectType.UNKNOWN)
                    .message(meta.message)
                    .build();
        }

        String defaultBranch = meta.defaultBranch;
        String targetBranch  = (storedBranch != null && !storedBranch.isBlank())
                ? storedBranch.strip()
                : defaultBranch;

        // ── Step B: Detect marker files on the target branch ──────────────────
        boolean hasPomXml        = probeFile(owner, repoName, targetBranch, "pom.xml");
        boolean hasBuildGradle   = probeFile(owner, repoName, targetBranch, "build.gradle");
        boolean hasSettingsGradle= probeFile(owner, repoName, targetBranch, "settings.gradle");
        boolean hasPackageJson   = probeFile(owner, repoName, targetBranch, "package.json");
        boolean hasSrcMainJava   = probeFile(owner, repoName, targetBranch, "src/main/java");

        GitHubDetectedProjectType projectType =
                deriveProjectType(hasPomXml, hasBuildGradle, hasSettingsGradle,
                                  hasPackageJson, hasSrcMainJava);

        log.info("[GitHubCheck] {} reachable, branch={}, type={}", safeRef, targetBranch, projectType);

        return GitHubRepositoryCheckResponse.builder()
                .reachable(true)
                .provider("GITHUB")
                .owner(owner)
                .repositoryName(repoName)
                .repositoryUrl(repoUrl)
                .branch(targetBranch)
                .defaultBranch(defaultBranch)
                .detectedProjectType(projectType)
                .hasPomXml(hasPomXml)
                .hasBuildGradle(hasBuildGradle)
                .hasSettingsGradle(hasSettingsGradle)
                .hasPackageJson(hasPackageJson)
                .hasSrcMainJava(hasSrcMainJava)
                .message("Repository is reachable. Detected project type: " + projectType.name() + ".")
                .build();
    }

    // ── GitHub API helpers ────────────────────────────────────────────────────

    /**
     * Calls {@code GET /repos/{owner}/{repo}} and extracts the default branch.
     *
     * @return a {@link RepoMeta} indicating reachability and the default branch name.
     */
    private RepoMeta fetchRepoMeta(String owner, String repoName) {
        // Build URL from trusted constant — never from user input
        String apiPath = GITHUB_API_BASE + "/repos/" + owner + "/" + repoName;
        try {
            HttpURLConnection conn = openConnection(apiPath);
            int status = conn.getResponseCode();

            if (status == 200) {
                try (InputStream is = conn.getInputStream()) {
                    JsonNode root = objectMapper.readTree(is);
                    String defaultBranch = root.path("default_branch").asText("main");
                    return RepoMeta.reachable(defaultBranch);
                }
            } else if (status == 301 || status == 302) {
                // Moved — treat as not reachable for safety; do not follow blindly
                return RepoMeta.unreachable("Repository has been moved or renamed (HTTP " + status + ").");
            } else if (status == 404) {
                return RepoMeta.unreachable("Repository not found or is private (HTTP 404).");
            } else if (status == 403) {
                return RepoMeta.unreachable("Access denied — repository may be private or rate-limited (HTTP 403).");
            } else {
                return RepoMeta.unreachable("GitHub returned HTTP " + status + ".");
            }
        } catch (IOException e) {
            log.warn("[GitHubCheck] I/O error reaching GitHub API for [{}/{}]: {}", owner, repoName, e.getMessage());
            return RepoMeta.unreachable("Could not reach GitHub: " + e.getMessage());
        }
    }

    /**
     * Checks whether a specific path exists in the repository by calling
     * {@code GET /repos/{owner}/{repo}/contents/{path}?ref={branch}}.
     *
     * <p>Returns {@code true} on HTTP 200, {@code false} on 404 or any error.
     */
    boolean probeFile(String owner, String repoName, String branch, String filePath) {
        String apiPath = GITHUB_API_BASE + "/repos/" + owner + "/" + repoName
                + "/contents/" + filePath + "?ref=" + branch;
        try {
            HttpURLConnection conn = openConnection(apiPath);
            int status = conn.getResponseCode();
            conn.disconnect();
            return status == 200;
        } catch (IOException e) {
            // Treat I/O error as file-not-found — do not propagate
            return false;
        }
    }

    /**
     * Opens an {@link HttpURLConnection} to a trusted
     * {@code https://api.github.com} URL with security constraints applied.
     *
     * @param trustedApiUrl must start with {@code https://api.github.com}.
     */
    private HttpURLConnection openConnection(String trustedApiUrl) throws IOException {
        // Safety: only call api.github.com — verify before opening
        if (!trustedApiUrl.startsWith(GITHUB_API_BASE)) {
            throw new IllegalArgumentException(
                    "Attempted to connect to unexpected host: [REDACTED]");
        }

        URL url = URI.create(trustedApiUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestProperty("Accept", ACCEPT_HEADER);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        // Never send Authorization header — public repos only
        conn.setInstanceFollowRedirects(false); // we handle redirects explicitly
        conn.setDoOutput(false);
        return conn;
    }

    // ── Project type detection ────────────────────────────────────────────────

    /**
     * Derives the most specific {@link GitHubDetectedProjectType} from the
     * detected marker file flags.
     *
     * <p>Precedence:
     * <ol>
     *   <li>SPRING_BOOT  — pom.xml AND src/main/java
     *   <li>JAVA_MAVEN   — pom.xml only
     *   <li>JAVA_GRADLE  — build.gradle OR settings.gradle
     *   <li>NODE         — package.json
     *   <li>UNKNOWN      — nothing matched
     * </ol>
     */
    GitHubDetectedProjectType deriveProjectType(boolean hasPomXml,
                                                boolean hasBuildGradle,
                                                boolean hasSettingsGradle,
                                                boolean hasPackageJson,
                                                boolean hasSrcMainJava) {
        if (hasPomXml && hasSrcMainJava) return GitHubDetectedProjectType.SPRING_BOOT;
        if (hasPomXml)                   return GitHubDetectedProjectType.JAVA_MAVEN;
        if (hasBuildGradle || hasSettingsGradle) return GitHubDetectedProjectType.JAVA_GRADLE;
        if (hasPackageJson)              return GitHubDetectedProjectType.NODE;
        return GitHubDetectedProjectType.UNKNOWN;
    }

    // ── Internal result carrier ───────────────────────────────────────────────

    /** Lightweight value object for the repository-level API call result. */
    static final class RepoMeta {
        final boolean reachable;
        final String  defaultBranch; // null if not reachable
        final String  message;

        private RepoMeta(boolean reachable, String defaultBranch, String message) {
            this.reachable     = reachable;
            this.defaultBranch = defaultBranch;
            this.message       = message;
        }

        static RepoMeta reachable(String defaultBranch) {
            return new RepoMeta(true, defaultBranch, null);
        }

        static RepoMeta unreachable(String message) {
            return new RepoMeta(false, null, message);
        }
    }
}
