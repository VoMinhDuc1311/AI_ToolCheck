package com.aitoolcheck.ai_toolcheck1_backend.common;

import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * Stateless helper that validates and normalises GitHub repository URLs.
 *
 * <p>Allowed input shapes:
 * <ul>
 *   <li>{@code https://github.com/owner/repo}
 *   <li>{@code https://github.com/owner/repo.git}
 * </ul>
 *
 * <p>Rejected:
 * <ul>
 *   <li>http:// (insecure scheme)
 *   <li>Any host other than {@code github.com}
 *   <li>Userinfo / credentials in the URL
 *   <li>Query parameters
 *   <li>Fragments
 *   <li>Path that does not match {@code /owner/repo}
 * </ul>
 *
 * <p>Normalised output: {@code https://github.com/owner/repo} (no trailing .git).
 */
public final class GitHubRepositoryUrlParser {

    /** Max raw input length accepted before even attempting to parse. */
    private static final int MAX_URL_LENGTH = 500;

    /** Max branch name length. */
    private static final int MAX_BRANCH_LENGTH = 120;

    /**
     * Pattern for a valid GitHub path: /owner/repo
     * owner and repo each: alphanumeric, hyphens, dots, underscores; 1–100 chars.
     * Must not start or end with a hyphen.
     */
    private static final Pattern PATH_PATTERN = Pattern.compile(
            "^/([A-Za-z0-9][A-Za-z0-9._-]{0,98}[A-Za-z0-9]|[A-Za-z0-9])" +
            "/([A-Za-z0-9][A-Za-z0-9._-]{0,98}[A-Za-z0-9]|[A-Za-z0-9])(\\.git)?$"
    );

    /**
     * Branch name: printable ASCII, no slashes, no backslashes, no whitespace,
     * no control characters, max 120 chars.
     */
    private static final Pattern BRANCH_PATTERN = Pattern.compile(
            "^[\\x21-\\x7E&&[^/\\\\]]{1,120}$"
    );

    private GitHubRepositoryUrlParser() { /* utility class */ }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Validates and normalises a raw repository URL.
     *
     * @param raw the URL string as received from the request (may be null/blank).
     * @return the normalised URL ({@code https://github.com/owner/repo}),
     *         or {@code null} if the input is null or blank.
     * @throws BadRequestException if the URL is non-blank but invalid.
     */
    public static String normalise(String raw) {
        if (raw == null) return null;
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) return null;

        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new BadRequestException("repositoryUrl is too long (max " + MAX_URL_LENGTH + " characters)");
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new BadRequestException("repositoryUrl is not a valid URL: " + trimmed);
        }

        // 1. Scheme must be https
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new BadRequestException(
                    "repositoryUrl must use HTTPS (https://github.com/owner/repo)");
        }

        // 2. Host must be github.com (case-insensitive)
        String host = uri.getHost();
        if (host == null || !"github.com".equalsIgnoreCase(host)) {
            throw new BadRequestException(
                    "repositoryUrl must point to github.com (https://github.com/owner/repo)");
        }

        // 3. No userinfo (credentials)
        if (uri.getRawUserInfo() != null) {
            throw new BadRequestException(
                    "repositoryUrl must not contain credentials or userinfo");
        }

        // 4. No query string
        if (uri.getRawQuery() != null) {
            throw new BadRequestException(
                    "repositoryUrl must not contain query parameters");
        }

        // 5. No fragment
        if (uri.getRawFragment() != null) {
            throw new BadRequestException(
                    "repositoryUrl must not contain a fragment (#)");
        }

        // 6. Path must match /owner/repo[.git]
        String path = uri.getPath();
        if (path == null || !PATH_PATTERN.matcher(path).matches()) {
            throw new BadRequestException(
                    "repositoryUrl must be in the form https://github.com/owner/repo");
        }

        // 7. Normalise: strip trailing .git, rebuild canonical URL
        String normPath = path.endsWith(".git")
                ? path.substring(0, path.length() - 4)
                : path;

        return "https://github.com" + normPath;
    }

    /**
     * Validates and normalises a branch name.
     *
     * @param raw the branch as received (may be null/blank).
     * @return trimmed branch, or {@code null} if null/blank.
     * @throws BadRequestException if the branch is non-blank but invalid.
     */
    public static String normaliseBranch(String raw) {
        if (raw == null) return null;
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) return null;

        if (trimmed.length() > MAX_BRANCH_LENGTH) {
            throw new BadRequestException(
                    "repositoryBranch is too long (max " + MAX_BRANCH_LENGTH + " characters)");
        }

        if (!BRANCH_PATTERN.matcher(trimmed).matches()) {
            throw new BadRequestException(
                    "repositoryBranch contains invalid characters (no slashes, backslashes, or whitespace allowed)");
        }

        return trimmed;
    }

    // ── Derived metadata ──────────────────────────────────────────────────────

    /**
     * Extracts the owner segment from a normalised GitHub URL.
     *
     * @param normalisedUrl a URL already processed by {@link #normalise(String)}.
     * @return owner string, or {@code null} if the URL is null.
     */
    public static String extractOwner(String normalisedUrl) {
        if (normalisedUrl == null) return null;
        // Pattern: https://github.com/{owner}/{repo}
        String path = normalisedUrl.substring("https://github.com".length()); // e.g. /owner/repo
        String[] parts = path.split("/");
        return parts.length >= 2 ? parts[1] : null;
    }

    /**
     * Extracts the repository name from a normalised GitHub URL.
     *
     * @param normalisedUrl a URL already processed by {@link #normalise(String)}.
     * @return repo name, or {@code null} if the URL is null.
     */
    public static String extractRepo(String normalisedUrl) {
        if (normalisedUrl == null) return null;
        String path = normalisedUrl.substring("https://github.com".length());
        String[] parts = path.split("/");
        return parts.length >= 3 ? parts[2] : null;
    }

    /**
     * Returns the provider label when a normalised URL is present.
     *
     * @param normalisedUrl a URL already processed by {@link #normalise(String)}.
     * @return {@code "GITHUB"} if the URL is non-null, {@code null} otherwise.
     */
    public static String deriveProvider(String normalisedUrl) {
        return normalisedUrl != null ? "GITHUB" : null;
    }
}
