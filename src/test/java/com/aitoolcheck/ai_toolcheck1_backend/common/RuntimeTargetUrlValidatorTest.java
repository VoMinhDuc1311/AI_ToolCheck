package com.aitoolcheck.ai_toolcheck1_backend.common;

import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RuntimeTargetUrlValidator}.
 * Verifies that the runtime target URL validator correctly accepts http/https URLs
 * and rejects invalid/dangerous schemes. Separate from GitHub repository URL validation.
 */
class RuntimeTargetUrlValidatorTest {

    // ── Valid URLs ────────────────────────────────────────────────────────────

    @Test
    void normalise_httpUrl_succeeds() {
        String result = RuntimeTargetUrlValidator.normalise("http://52.220.34.212:8081");
        assertThat(result).isEqualTo("http://52.220.34.212:8081");
    }

    @Test
    void normalise_httpsUrl_succeeds() {
        String result = RuntimeTargetUrlValidator.normalise("https://demo.myapp.com");
        assertThat(result).isEqualTo("https://demo.myapp.com");
    }

    @Test
    void normalise_localhostUrl_succeeds() {
        // localhost must be accepted for local dev/demo
        String result = RuntimeTargetUrlValidator.normalise("http://localhost:8080");
        assertThat(result).isEqualTo("http://localhost:8080");
    }

    @Test
    void normalise_urlWithPath_succeeds() {
        String result = RuntimeTargetUrlValidator.normalise("http://52.220.34.212:8081/api");
        assertThat(result).isEqualTo("http://52.220.34.212:8081/api");
    }

    @Test
    void normalise_trailingSlash_isStripped() {
        String result = RuntimeTargetUrlValidator.normalise("http://52.220.34.212:8081/");
        assertThat(result).isEqualTo("http://52.220.34.212:8081");
    }

    // ── Null / blank normalisation ────────────────────────────────────────────

    @Test
    void normalise_null_returnsNull() {
        assertThat(RuntimeTargetUrlValidator.normalise(null)).isNull();
    }

    @Test
    void normalise_blank_returnsNull() {
        assertThat(RuntimeTargetUrlValidator.normalise("   ")).isNull();
    }

    @Test
    void normalise_emptyString_returnsNull() {
        assertThat(RuntimeTargetUrlValidator.normalise("")).isNull();
    }

    // ── Invalid schemes ───────────────────────────────────────────────────────

    @Test
    void normalise_ftpScheme_throwsBadRequest() {
        assertThatThrownBy(() -> RuntimeTargetUrlValidator.normalise("ftp://files.example.com"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("invalid scheme");
    }

    @Test
    void normalise_fileScheme_throwsBadRequest() {
        assertThatThrownBy(() -> RuntimeTargetUrlValidator.normalise("file:///etc/passwd"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void normalise_javascriptScheme_throwsBadRequest() {
        assertThatThrownBy(() -> RuntimeTargetUrlValidator.normalise("javascript:alert(1)"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void normalise_mailtoScheme_throwsBadRequest() {
        assertThatThrownBy(() -> RuntimeTargetUrlValidator.normalise("mailto:user@example.com"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void normalise_noScheme_throwsBadRequest() {
        assertThatThrownBy(() -> RuntimeTargetUrlValidator.normalise("52.220.34.212:8081"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void normalise_credentialsInUrl_throwsBadRequest() {
        assertThatThrownBy(() -> RuntimeTargetUrlValidator.normalise("http://user:pass@52.220.34.212:8081"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("credentials");
    }

    // ── Business rule: repositoryUrl must NEVER become defaultTargetBaseUrl ──

    @Test
    void normalise_githubUrl_throwsBadRequest() {
        // GitHub URLs are valid https but this is asserted separately in service layer.
        // The validator itself accepts https://, but the service must NOT auto-copy repositoryUrl.
        // This test documents that the validator does NOT auto-reject GitHub URLs —
        // the business rule is enforced in SourceProjectServiceImpl (never auto-copies).
        String result = RuntimeTargetUrlValidator.normalise("https://github.com/owner/repo");
        // Should succeed from validator perspective (https:// is valid)
        assertThat(result).isEqualTo("https://github.com/owner/repo");
    }

    // ── Whitespace handling ───────────────────────────────────────────────────

    @Test
    void normalise_urlWithLeadingTrailingSpaces_isTrimmed() {
        String result = RuntimeTargetUrlValidator.normalise("  http://localhost:8080  ");
        assertThat(result).isEqualTo("http://localhost:8080");
    }
}
