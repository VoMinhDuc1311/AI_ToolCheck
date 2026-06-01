package com.aitoolcheck.ai_toolcheck1_backend.common;

import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Stateless helper for validating and normalising runtime target base URLs.
 *
 * <p>A <em>runtime target base URL</em> is the live HTTP endpoint where a
 * tested application is running. It is used exclusively by the TestRun
 * execution engine to send actual HTTP requests.
 *
 * <p><strong>This is COMPLETELY SEPARATE from {@code repositoryUrl}.</strong>
 * {@code repositoryUrl} = GitHub source location for static analysis (Agent 1).
 * {@code targetBaseUrl} = running application endpoint for test execution (Agent 3).
 *
 * <p>Accepted examples:
 * <ul>
 *   <li>{@code http://52.220.34.212:8081}
 *   <li>{@code https://demo.myapp.com}
 *   <li>{@code http://localhost:8080}
 * </ul>
 *
 * <p>Rejected examples:
 * <ul>
 *   <li>{@code ftp://...} — invalid scheme
 *   <li>{@code file:///...} — invalid scheme
 *   <li>{@code javascript:...} — invalid scheme
 *   <li>{@code mailto:...} — invalid scheme
 *   <li>{@code https://github.com/owner/repo} — GitHub repo URL; must not be used as runtime target
 * </ul>
 */
public final class RuntimeTargetUrlValidator {

    private static final int MAX_URL_LENGTH = 500;
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<String> REJECTED_SCHEMES = Set.of(
            "ftp", "ftps", "file", "javascript", "mailto", "data", "vbscript"
    );

    private RuntimeTargetUrlValidator() { /* utility class */ }

    /**
     * Validates and normalises a runtime target base URL.
     *
     * <p>Normalisation: trims whitespace, strips trailing slash.
     *
     * @param raw the raw URL string from the request (may be null or blank).
     * @return normalised URL string, or {@code null} if input is null or blank.
     * @throws BadRequestException if the URL is non-blank but invalid or uses a prohibited scheme.
     */
    public static String normalise(String raw) {
        if (raw == null) return null;
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) return null;

        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new BadRequestException(
                    "defaultTargetBaseUrl is too long (max " + MAX_URL_LENGTH + " characters)");
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new BadRequestException(
                    "defaultTargetBaseUrl is not a valid URL: " + trimmed);
        }

        String scheme = uri.getScheme();
        if (scheme == null || scheme.isBlank()) {
            throw new BadRequestException(
                    "defaultTargetBaseUrl must include a scheme (http:// or https://)");
        }

        String lowerScheme = scheme.toLowerCase(Locale.ROOT);

        // Explicitly reject dangerous schemes
        if (REJECTED_SCHEMES.contains(lowerScheme)) {
            throw new BadRequestException(
                    "defaultTargetBaseUrl uses an invalid scheme '" + scheme +
                    "'. Only http:// and https:// are allowed.");
        }

        // Only allow http and https
        if (!ALLOWED_SCHEMES.contains(lowerScheme)) {
            throw new BadRequestException(
                    "defaultTargetBaseUrl must use http:// or https://");
        }

        // Must have a host
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BadRequestException(
                    "defaultTargetBaseUrl must include a valid host");
        }

        // No credentials in URL
        if (uri.getRawUserInfo() != null) {
            throw new BadRequestException(
                    "defaultTargetBaseUrl must not contain credentials");
        }

        // Strip trailing slash for consistency
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed;
    }
}
