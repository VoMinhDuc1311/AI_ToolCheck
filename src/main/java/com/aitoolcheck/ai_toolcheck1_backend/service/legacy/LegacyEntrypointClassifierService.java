package com.aitoolcheck.ai_toolcheck1_backend.service.legacy;

import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies legacy Java source files as API entrypoint candidates or helpers.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>Uses purely rule-based heuristics — no AI call, no hardcoded file names.</li>
 *   <li>Stateless singleton — safe for Spring singleton scope.</li>
 *   <li>Scores are additive: positive = entrypoint signals, negative = helper signals.</li>
 *   <li>A file is a candidate when {@code score >= CANDIDATE_THRESHOLD} and not in the
 *       explicit helper exclusion set.</li>
 * </ul>
 *
 * <h3>Candidate types</h3>
 * <ul>
 *   <li>SERVLET — extends HttpServlet, has doGet/doPost/doPut/doDelete</li>
 *   <li>STRUTS_ACTION — class name contains "Action", method execute()</li>
 *   <li>CONTROLLER_LIKE — name contains Controller, Gateway, Handler, Endpoint</li>
 *   <li>ROUTER — routing/dispatch logic detected (switch/if on request URI/action)</li>
 *   <li>HELPER — utility/db/config/dto class — not a candidate</li>
 *   <li>UNKNOWN — insufficient signals, treated as non-candidate</li>
 * </ul>
 */
@Slf4j
@Service
public class LegacyEntrypointClassifierService {

    /**
     * Minimum score required to be considered a candidate.
     * Files at or above this threshold get an AI inference job.
     */
    private static final int CANDIDATE_THRESHOLD = 20;

    // ── Name-pattern signals (class name / file name / path) ─────────────────

    /** Class/file name suffixes that strongly indicate a helper/non-entrypoint. */
    private static final Set<String> HELPER_NAME_PATTERNS = Set.of(
            "dbbridge", "repository", "dao", "mapper", "util", "utils", "helper",
            "writer", "dto", "entity", "model", "config", "configuration",
            "constant", "constants", "factory", "exception", "filter", "listener",
            "scheduler", "job", "task", "event", "builder", "converter", "transformer",
            "validator", "formatter", "properties", "settings", "base", "abstract",
            "mixin", "interceptor", "security", "auth", "annotation", "enum", "type",
            "provider", "adapter", "wrapper", "proxy", "registry", "manager",
            "datasource", "connection", "pool", "cache", "session", "context",
            "initializer", "init", "startup", "loader", "reader", "writer",
            "extractor", "parser", "serializer", "deserializer", "codec",
            "aspect", "pointcut"
    );

    /** Path segments that indicate non-entrypoint packages. */
    private static final Set<String> HELPER_PATH_SEGMENTS = Set.of(
            "/dto/", "/model/", "/entity/", "/config/", "/util/", "/utils/",
            "/helper/", "/repository/", "/dao/", "/mapper/", "/exception/",
            "/constant/", "/annotation/", "/filter/", "/listener/", "/scheduler/",
            "/event/", "/security/", "/auth/", "/init/", "/startup/", "/codec/"
    );

    /** Class/file name patterns that are strong positive signals for entrypoints. */
    private static final Set<String> ENTRYPOINT_NAME_PATTERNS = Set.of(
            "servlet", "gateway", "dispatcher", "handler", "controller",
            "endpoint", "action", "router", "front", "facade", "resource",
            "restcontroller", "api", "service"   // "service" can be both, but it can hold request routing
    );

    // ── Content signals ───────────────────────────────────────────────────────

    /** HTTP Servlet method names — strong positive signals. */
    private static final Set<String> SERVLET_METHOD_NAMES = Set.of(
            "doget", "dopost", "doput", "dodelete", "dooptions", "dohead", "service"
    );

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Classify a single SourceFile.
     *
     * @param file SourceFile with fileName, filePath, and sourceContent populated.
     * @return Classification result with candidate flag and reasons.
     */
    public LegacyEntrypointCandidateResult classify(SourceFile file) {
        String fileName   = safeStr(file.getFileName());
        String filePath   = safeStr(file.getFilePath());
        String content    = safeStr(file.getSourceContent());
        String className  = extractClassName(fileName);
        String classLower = className.toLowerCase(Locale.ROOT);
        String pathLower  = filePath.toLowerCase(Locale.ROOT);

        List<String> reasons = new ArrayList<>();
        int score = 0;

        // ── STEP 1: Hard exclusion by name pattern ────────────────────────────
        if (isHardExclusion(classLower, pathLower, reasons)) {
            return buildResult(file, false, "HELPER", score, reasons);
        }

        // ── STEP 2: Content-based HTTP signals (strong positive) ──────────────
        score += scoreHttpServletSignals(content, reasons);
        score += scoreRequestResponseSignals(content, reasons);
        score += scoreRoutingSignals(content, reasons);

        // ── STEP 3: Struts/Action signals ─────────────────────────────────────
        score += scoreStrutsSignals(className, classLower, content, reasons);

        // ── STEP 4: Name-based positive signals ───────────────────────────────
        score += scoreNamePositiveSignals(classLower, reasons);

        // ── STEP 5: Helper content signals (negative) ─────────────────────────
        score += scoreHelperContentSignals(content, reasons);

        // ── Decision ──────────────────────────────────────────────────────────
        boolean candidate = score >= CANDIDATE_THRESHOLD;
        String candidateType = determineCandidateType(classLower, content, score);

        log.debug("[Classifier] file={} score={} candidate={} type={} reasons={}",
                fileName, score, candidate, candidateType, reasons);

        return buildResult(file, candidate, candidateType, score, reasons);
    }

    /**
     * Classify a list of SourceFiles and return all results.
     */
    public List<LegacyEntrypointCandidateResult> classifyAll(List<SourceFile> files) {
        List<LegacyEntrypointCandidateResult> results = new ArrayList<>();
        for (SourceFile file : files) {
            results.add(classify(file));
        }
        long candidates = results.stream().filter(LegacyEntrypointCandidateResult::isCandidate).count();
        log.info("[Classifier] Classified {} files: {} candidates, {} skipped",
                files.size(), candidates, files.size() - candidates);
        return results;
    }

    // =========================================================================
    // Classification Steps
    // =========================================================================

    /**
     * Returns true if the class is definitely a helper based on name/path patterns.
     * Hard exclusions bypass all positive scoring — no AI job will be created.
     */
    private boolean isHardExclusion(String classLower, String pathLower, List<String> reasons) {
        // Path-based exclusion
        for (String segment : HELPER_PATH_SEGMENTS) {
            if (pathLower.contains(segment)) {
                reasons.add("SKIP: path contains helper segment '" + segment + "'");
                return true;
            }
        }

        // Name-based exclusion — match suffix or contains
        for (String pattern : HELPER_NAME_PATTERNS) {
            if (classLower.endsWith(pattern) || classLower.contains(pattern + "impl")) {
                reasons.add("SKIP: class name matches helper pattern '" + pattern + "'");
                return true;
            }
        }

        return false;
    }

    /** Score HTTP Servlet method signals. Max +60. */
    private int scoreHttpServletSignals(String content, List<String> reasons) {
        int score = 0;

        if (content.contains("extends HttpServlet")) {
            score += 40;
            reasons.add("+40: extends HttpServlet");
        }

        for (String method : SERVLET_METHOD_NAMES) {
            // look for method declaration, not just usage
            if (containsMethodDeclaration(content, method)) {
                int pts = "service".equals(method) ? 10 : 20;
                score += pts;
                reasons.add("+" + pts + ": declares " + method + "()");
            }
        }

        if (content.contains("HttpServletRequest") || content.contains("HttpServletResponse")) {
            score += 15;
            reasons.add("+15: imports/uses HttpServletRequest/Response");
        }

        return score;
    }

    /** Score request.getXxx / response.getWriter usage signals. Max +30. */
    private int scoreRequestResponseSignals(String content, List<String> reasons) {
        int score = 0;

        if (content.contains("request.getParameter(")) {
            score += 15;
            reasons.add("+15: request.getParameter() usage");
        }
        if (content.contains("request.getRequestURI()") || content.contains("request.getPathInfo()")) {
            score += 15;
            reasons.add("+15: request.getRequestURI/getPathInfo() usage");
        }
        if (content.contains("response.getWriter()") || content.contains("response.sendRedirect(")
                || content.contains("response.setContentType(")) {
            score += 10;
            reasons.add("+10: response.getWriter/sendRedirect/setContentType() usage");
        }

        return score;
    }

    /** Score routing logic signals (switch/if on action/type/path). Max +25. */
    private int scoreRoutingSignals(String content, List<String> reasons) {
        int score = 0;

        // switch/case on action parameter or URI
        boolean hasSwitch = content.contains("switch") && (
                content.contains("getParameter(\"action\"") ||
                content.contains("getParameter(\"type\"") ||
                content.contains("getParameter(\"cmd\"") ||
                content.contains("getRequestURI") ||
                content.contains("getPathInfo"));
        if (hasSwitch) {
            score += 20;
            reasons.add("+20: switch/case routing on request parameter/URI");
        }

        // if/else routing on action
        boolean hasIfElseRoute = (content.contains("if") || content.contains("else")) &&
                content.contains("getParameter(\"action\"");
        if (hasIfElseRoute && !hasSwitch) {
            score += 15;
            reasons.add("+15: if/else routing on action parameter");
        }

        // RequestDispatcher / forward
        if (content.contains("RequestDispatcher") || content.contains("getRequestDispatcher(")) {
            score += 10;
            reasons.add("+10: RequestDispatcher/forward usage");
        }

        return score;
    }

    /** Score Struts Action patterns. Max +35. */
    private int scoreStrutsSignals(String className, String classLower, String content, List<String> reasons) {
        int score = 0;

        if (classLower.endsWith("action")) {
            score += 15;
            reasons.add("+15: class name ends with 'Action' (Struts pattern)");
        }

        if (containsMethodDeclaration(content, "execute")) {
            score += 20;
            reasons.add("+20: declares execute() method (Struts Action)");
        }

        if (content.contains("ActionMapping") || content.contains("ActionForward") || content.contains("ActionForm")) {
            score += 15;
            reasons.add("+15: Struts Action imports (ActionMapping/ActionForward/ActionForm)");
        }

        return score;
    }

    /** Score positive signals from class/file name. Max +20. */
    private int scoreNamePositiveSignals(String classLower, List<String> reasons) {
        int score = 0;

        for (String pattern : ENTRYPOINT_NAME_PATTERNS) {
            if (classLower.contains(pattern)) {
                int pts = ("gateway".equals(pattern) || "servlet".equals(pattern)
                        || "controller".equals(pattern) || "resource".equals(pattern)) ? 20 : 10;
                score += pts;
                reasons.add("+" + pts + ": class name contains '" + pattern + "'");
                break; // avoid stacking name bonuses
            }
        }

        return score;
    }

    /** Apply negative score if content looks like a pure helper. Max -40. */
    private int scoreHelperContentSignals(String content, List<String> reasons) {
        int score = 0;

        // Pure DB/SQL helper
        boolean hasJdbc = content.contains("Connection") && content.contains("PreparedStatement") &&
                !content.contains("HttpServlet") && !content.contains("HttpServletRequest");
        if (hasJdbc) {
            score -= 30;
            reasons.add("-30: looks like a JDBC utility/bridge (Connection + PreparedStatement, no HTTP)");
        }

        // Pure utility with no request/response
        boolean noHttpSignal = !content.contains("HttpServlet") &&
                !content.contains("HttpServletRequest") &&
                !content.contains("HttpServletResponse") &&
                !content.contains("ActionMapping");
        if (noHttpSignal && content.contains("class ")) {
            score -= 10;
            reasons.add("-10: no HTTP servlet/request/response imports detected");
        }

        return score;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String determineCandidateType(String classLower, String content, int score) {
        if (score < CANDIDATE_THRESHOLD) return "HELPER";
        if (content.contains("extends HttpServlet")) return "SERVLET";
        if (containsMethodDeclaration(content, "execute") && classLower.endsWith("action")) return "STRUTS_ACTION";
        if (classLower.contains("gateway") || classLower.contains("router")
                || classLower.contains("dispatcher")) return "ROUTER";
        if (classLower.contains("controller") || classLower.contains("handler")
                || classLower.contains("endpoint")) return "CONTROLLER_LIKE";
        if (score >= CANDIDATE_THRESHOLD) return "UNKNOWN_ENTRYPOINT";
        return "UNKNOWN";
    }

    private boolean containsMethodDeclaration(String content, String methodName) {
        // Looks for "methodName(" preceded by whitespace/keyword — not just usage
        return content.contains(methodName + "(") &&
                (content.contains("void " + methodName + "(") ||
                 content.contains("String " + methodName + "(") ||
                 content.contains("ActionForward " + methodName + "(") ||
                 content.contains("protected " + methodName + "(") ||
                 content.contains("public " + methodName + "(") ||
                 content.contains("private " + methodName + "("));
    }

    private String extractClassName(String fileName) {
        if (fileName == null) return "";
        int dotIdx = fileName.lastIndexOf('.');
        return dotIdx >= 0 ? fileName.substring(0, dotIdx) : fileName;
    }

    private String safeStr(String s) {
        return s == null ? "" : s;
    }

    private LegacyEntrypointCandidateResult buildResult(SourceFile file, boolean candidate,
                                                         String type, int score,
                                                         List<String> reasons) {
        return LegacyEntrypointCandidateResult.builder()
                .sourceFileId(file.getId())
                .fileName(file.getFileName())
                .filePath(file.getFilePath())
                .candidate(candidate)
                .candidateType(type)
                .score(score)
                .reasons(reasons)
                .build();
    }
}
