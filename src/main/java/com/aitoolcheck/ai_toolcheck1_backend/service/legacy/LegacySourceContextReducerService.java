package com.aitoolcheck.ai_toolcheck1_backend.service.legacy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reduces legacy Java source code to a focused "routing skeleton" before
 * sending to the Ollama fallback provider.
 *
 * <h3>Goal</h3>
 * Ollama local models have limited context windows and are slower than Gemini.
 * Sending the full source file (may be thousands of lines) causes:
 * <ul>
 *   <li>Timeout (120 s may still not be enough for large 7B models)</li>
 *   <li>Worse inference quality due to irrelevant code drowning out routing logic</li>
 * </ul>
 *
 * <h3>Reduction strategy</h3>
 * <ol>
 *   <li>Keep: package declaration, significant imports, class declaration</li>
 *   <li>Keep: extends/implements clauses</li>
 *   <li>Keep: method signatures that are routing-relevant</li>
 *   <li>Keep: lines with HTTP signals (doGet, doPost, request.getParameter, etc.)</li>
 *   <li>Keep: switch/case/if-else routing blocks</li>
 *   <li>Keep: response.getWriter / sendRedirect / setContentType</li>
 *   <li>Remove: getter/setter pairs, Javadoc, long SQL strings, constants, utility methods</li>
 * </ol>
 *
 * <h3>Safety</h3>
 * If the original content is already short enough (under {@value #OLLAMA_MAX_CHARS} chars),
 * the original content is returned unchanged.
 */
@Slf4j
@Service
public class LegacySourceContextReducerService {

    /**
     * If content is shorter than this, return as-is.
     * ~3000 chars ≈ 750 tokens — well within Ollama 7B context.
     */
    private static final int OLLAMA_MAX_CHARS = 4000;

    /**
     * Maximum chars we will send to Ollama after reduction.
     * Keeps prompt+content well under 8K tokens for 7B models.
     */
    private static final int REDUCED_MAX_CHARS = 3500;

    // ── Line-level keep patterns ──────────────────────────────────────────────

    private static final List<String> KEEP_PATTERNS = List.of(
            // Class/package structure
            "package ",
            "import ",
            "class ",
            "interface ",
            "extends ",
            "implements ",
            "@Override",
            // HTTP Servlet methods
            "doGet(", "doPost(", "doPut(", "doDelete(", "doOptions(", "doHead(",
            "service(",
            // Servlet contract types
            "HttpServlet", "HttpServletRequest", "HttpServletResponse",
            // Request accessors
            "request.getParameter(", "request.getRequestURI(", "request.getPathInfo(",
            "request.getMethod(", "request.getHeader(",
            // Response write
            "response.getWriter(", "response.sendRedirect(", "response.setContentType(",
            "response.setStatus(",
            // Routing constructs
            "switch ", "case ", "if (", "} else {", "else if (",
            "ActionMapping", "ActionForward", "execute(",
            // Method access modifiers (to keep method signatures)
            "public ", "protected ",
            // Common dispatch patterns
            "dispatch", "forward(", "include(",
            "RequestDispatcher"
    );

    private static final List<String> SKIP_PATTERNS = List.of(
            // Getters/setters
            "get" + "    ",   // heuristic: 4-space indent "getXxx"
            "set" + "    ",
            // Logging (usually noise)
            "log.debug(", "logger.debug(", "log.trace(", "logger.trace(",
            // Long Javadoc / comments (handled separately at block level)
            "* @param", "* @return", "* @throws", "* @author",
            // SQL
            "SELECT ", "INSERT INTO", "UPDATE ", "DELETE FROM",
            "PreparedStatement", "ResultSet", "executeQuery",
            // Boilerplate
            "serialVersionUID",
            // Empty lines (handled below)
            ""
    );

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Reduce source content for Ollama.
     *
     * @param originalContent Full source file content.
     * @param fileName        File name — used only for logging.
     * @return Reduced content, or original if already short enough.
     */
    public String reduce(String originalContent, String fileName) {
        if (originalContent == null || originalContent.isBlank()) {
            return "";
        }

        if (originalContent.length() <= OLLAMA_MAX_CHARS) {
            log.debug("[ContextReducer] {} already short ({} chars) — no reduction", fileName, originalContent.length());
            return originalContent;
        }

        log.info("[ContextReducer] Reducing {} ({} chars) for Ollama...", fileName, originalContent.length());

        String[] lines = originalContent.split("\n");
        List<String> kept = new ArrayList<>();
        boolean inMultilineComment = false;
        boolean inGetterSetter = false;

        for (String rawLine : lines) {
            String line = rawLine.stripTrailing();
            String trimmed = line.strip();

            // Track multi-line comment blocks
            if (trimmed.startsWith("/*") || trimmed.startsWith("/**")) {
                inMultilineComment = true;
            }
            if (inMultilineComment) {
                if (trimmed.endsWith("*/")) {
                    inMultilineComment = false;
                }
                // Only keep if it contains routing-relevant info
                continue;
            }

            // Skip single-line comments (unless they're dividers that indicate method sections)
            if (trimmed.startsWith("//")) {
                continue;
            }

            // Skip blank lines (limit to one blank line in output)
            if (trimmed.isEmpty()) {
                if (!kept.isEmpty() && !kept.get(kept.size() - 1).isBlank()) {
                    kept.add("");
                }
                continue;
            }

            // Skip pure getter/setter lines (heuristic: return field or set field)
            if (isGetterSetterLine(trimmed)) {
                continue;
            }

            // Skip explicit noise
            if (isSkipLine(trimmed)) {
                continue;
            }

            // Keep if matches any keep pattern
            if (isKeepLine(trimmed)) {
                kept.add(line);
                continue;
            }

            // Keep closing braces (structure)
            if (trimmed.equals("}") || trimmed.equals("};") || trimmed.startsWith("} ")) {
                kept.add(line);
                continue;
            }

            // Keep opening braces attached to method/class declarations
            if (trimmed.equals("{")) {
                if (!kept.isEmpty()) {
                    kept.add(line);
                }
                continue;
            }

            // Default: skip line (not a match)
        }

        String reduced = String.join("\n", kept);

        // Trim to max chars if still too long
        if (reduced.length() > REDUCED_MAX_CHARS) {
            reduced = reduced.substring(0, REDUCED_MAX_CHARS) + "\n// [content truncated by ContextReducer]";
            log.warn("[ContextReducer] {} still too long after reduction — truncated to {} chars", fileName, REDUCED_MAX_CHARS);
        }

        int originalLen = originalContent.length();
        int reducedLen = reduced.length();
        log.info("[ContextReducer] {} reduced: {} chars → {} chars ({:.0f}% reduction)",
                fileName, originalLen, reducedLen,
                100.0 * (originalLen - reducedLen) / originalLen);

        return reduced;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean isKeepLine(String trimmed) {
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String pattern : KEEP_PATTERNS) {
            if (lower.contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isSkipLine(String trimmed) {
        for (String pattern : SKIP_PATTERNS) {
            if (!pattern.isEmpty() && trimmed.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean isGetterSetterLine(String trimmed) {
        // Pattern: "return this.fieldName;" or "this.fieldName = fieldName;"
        if (trimmed.startsWith("return this.") || trimmed.matches("this\\.\\w+\\s*=\\s*\\w+.*")) {
            return true;
        }
        // Pattern: simple return of field "return fieldName;"
        if (trimmed.startsWith("return ") && !trimmed.contains("(") && trimmed.endsWith(";")) {
            return true;
        }
        return false;
    }
}
