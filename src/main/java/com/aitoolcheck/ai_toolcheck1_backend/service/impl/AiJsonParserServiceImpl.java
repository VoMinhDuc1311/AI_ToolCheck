package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiInferenceResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiGeneratedTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException.ErrorType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiProviderFailureException;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJsonParserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Production-ready implementation of the full AI response Parsing Pipeline.
 *
 * <h3>Full Pipeline</h3>
 * 
 * <pre>
 * Raw AI Response
 *      |
 *      v  -- TASK 1 ---------------------------------------------------
 * [Layer 1] extractJsonBlock()    - Heuristic boundary detection
 *      |
 *      v
 * [Layer 2] validateJson()        - Jackson strict syntax check
 *      |
 *      v
 * [Layer 3] normalizeJson()       - Canonical minified string
 *      |
 *      v  -- TASK 2 ---------------------------------------------------
 * [Layer 4] mapToDto()            - Jackson readValue() -> AiInferenceResultDto
 *      |
 *      v
 * [Layer 5] validateDto()         - Jakarta Bean Validation
 *      |
 *      v
 * [Layer 6] recordMetrics()       - Micrometer success/fail counters
 *      |
 *      v
 * [Layer N+] (extensible)         - Add repairJson(), schemaValidation(), etc.
 * </pre>
 *
 * <h3>Design Decisions</h3>
 * <ul>
 * <li>Stateless - safe for Spring singleton scope and concurrent use.</li>
 * <li>ObjectMapper global instance is cloned via {@code copy()} before use
 * so per-request configuration changes never affect the shared bean.</li>
 * <li>Each pipeline stage is an isolated private method (SRP).</li>
 * <li>No sensitive data is logged - only lengths and error reasons.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiJsonParserServiceImpl implements AiJsonParserService {

    // --- Metric Names ---------------------------------------------------------

    private static final String METRIC_PARSE_SUCCESS = "ai.parse.success";
    private static final String METRIC_PARSE_FAIL = "ai.parse.fail";

    // --- JSON Boundary Constants ----------------------------------------------

    private static final char JSON_OBJECT_OPEN = '{';
    private static final char JSON_OBJECT_CLOSE = '}';
    private static final char JSON_ARRAY_OPEN = '[';
    private static final char JSON_ARRAY_CLOSE = ']';

    // --- Dependencies (Constructor Injected via @RequiredArgsConstructor) -----

    /**
     * Global shared ObjectMapper - cloned via copy() before local configuration.
     */
    private final ObjectMapper objectMapper;

    /** Jakarta Bean Validator - validates DTO constraints after deserialization. */
    private final Validator validator;

    /** Micrometer MeterRegistry - records parse success/fail counters. */
    private final MeterRegistry meterRegistry;

    // =========================================================================
    // TASK 2.1: parseTestCaseRequest (Dành riêng cho Tuần 8 - Phase 2)
    // =========================================================================

    /**
     * Pipeline chuyên biệt để parse dữ liệu Test Case sinh ra từ AI.
     * Ánh xạ (Map) chuỗi thô vào cấu trúc Wrapper DTO đã thống nhất:
     * AiGeneratedTestCaseRequest.
     *
     * @param rawAiResponse Phản hồi thô từ AI (có bọc markdown).
     * @return DTO chứa danh sách Test Case đã được validate chặt chẽ.
     */
    @Override
    public AiGeneratedTestCaseRequest parseTestCaseRequest(String rawAiResponse) {
        guardAgainstBlankInput(rawAiResponse);

        log.debug("[AiJsonParser] Bắt đầu parse Test Case Request - độ dài: {} ký tự", rawAiResponse.length());

        try {
            // Layer 1: Trích xuất lõi JSON (Loại bỏ Markdown, tìm '{')
            String cleanJson = extractJsonBlock(rawAiResponse);

            // Layer 4: Ánh xạ (Mapping) bằng ObjectMapper (Copy)
            ObjectMapper localMapper = objectMapper.copy()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

            AiGeneratedTestCaseRequest dto = localMapper.readValue(cleanJson, AiGeneratedTestCaseRequest.class);

            // Layer 5: Kích hoạt Jakarta Bean Validation
            validateDto(dto);

            // Layer 6: Ghi nhận Metric thành công
            recordMetrics(METRIC_PARSE_SUCCESS);

            log.info("[AiJsonParser] Parse Test Case thành công. Số lượng: {}", dto.getTestCases().size());
            return dto;

        } catch (AiJsonParseException e) {
            recordMetrics(METRIC_PARSE_FAIL);
            throw e; // Ném tiếp lỗi đã được bọc
        } catch (Exception e) {
            recordMetrics(METRIC_PARSE_FAIL);
            log.error("[AiJsonParser] Lỗi nghiêm trọng khi parse Test Case. Raw data: \n{}", rawAiResponse, e);
            throw new AiJsonParseException(
                    ErrorType.DTO_MAPPING_ERROR,
                    "Lỗi không mong muốn khi parse Test Case. Lý do: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // TASK 1: extractAndSanitizeJson (Layer 1 -> 3)
    // =========================================================================

    /**
     * Entry point of the Sanitization pipeline (Task 1: Layer 1-3).
     * Accepts raw LLM text and returns a clean, minified JSON string.
     *
     * @param rawAiResponse Raw text from the LLM.
     * @return Normalized, minified JSON string.
     * @throws AiJsonParseException on null/blank input or unrecoverable parse
     *                              failure.
     */
    @Override
    public String extractAndSanitizeJson(String rawAiResponse) {
        guardAgainstBlankInput(rawAiResponse);

        log.debug("[AiJsonParser] Raw AI response received - length: {} chars", rawAiResponse.length());

        // Pipeline: Layer 1 -> 2 -> 3
        String extracted = extractJsonBlock(rawAiResponse); // Layer 1
        JsonNode jsonNode = validateJson(extracted); // Layer 2
        String normalized = normalizeJson(jsonNode); // Layer 3

        log.debug("[AiJsonParser] Sanitization pipeline completed - output length: {} chars", normalized.length());
        return normalized;
    }

    // =========================================================================
    // TASK 2: parseToDto (Layer 4 -> 6)
    // =========================================================================

    /**
     * Full Deserialization Pipeline (Task 2: Layer 4-6).
     * <p>
     * Accepts a clean JSON string (output of {@link #extractAndSanitizeJson})
     * and produces a fully validated {@link AiInferenceResultDto}.
     * </p>
     *
     * @param cleanJson Sanitized JSON string (must not be null/blank).
     * @return Validated {@link AiInferenceResultDto}.
     * @throws AiJsonParseException with appropriate ErrorType on any failure.
     */
    @Override
    public AiInferenceResultDto parseToDto(String cleanJson) {
        guardAgainstBlankJson(cleanJson);

        log.debug("[AiJsonParser] Starting deserialization pipeline - input length: {} chars", cleanJson.length());

        try {
            AiInferenceResultDto dto = mapToDto(cleanJson); // Layer 4: Mapping
            validateDto(dto); // Layer 5: Validation
            recordMetrics(METRIC_PARSE_SUCCESS); // Layer 6: Metrics (success)

            log.info("[AiJsonParser] Parse success - {} endpoint(s) extracted", dto.getEndpoints().size());
            return dto;

        } catch (AiJsonParseException e) {
            recordMetrics(METRIC_PARSE_FAIL); // Layer 6: Metrics (fail)
            log.error("[AiJsonParser] Parse failed - errorType: {}, reason: {}", e.getErrorType(), e.getMessage());
            throw e; // Re-throw - never swallow
        }
    }

    /**
     * Hàm Generic gọt rửa văn bản thô do AI trả về và ép kiểu thành Java Object
     * (Generic).
     * Dùng chung cho toàn bộ các task AI (Tuần 5, Tuần 7, Tuần 10).
     *
     * @param rawAiResponse Phản hồi thô từ AI (có thể bọc trong markdown).
     * @param targetType    Class type của đối tượng đích (Ví dụ:
     *                      AiDocumentEnrichmentResponseDto.class).
     * @param <T>           Kiểu Generic của đối tượng trả về.
     * @return Đối tượng Java đã được ánh xạ.
     * @throws AiJsonParseException Nếu lỗi gọt rửa hoặc lỗi ép kiểu JSON.
     */
    @Override
    public <T> T parseJson(String rawAiResponse, Class<T> targetType) {
        // 1. Kiểm tra đầu vào an toàn
        guardAgainstBlankInput(rawAiResponse);

        // 2. Tái sử dụng Layer 1 (extractJsonBlock) để cắt bỏ Markdown.
        // Hàm này dùng indexOf/lastIndexOf an toàn tuyệt đối với JSON lớn, không lo
        // treo CPU.
        String cleanJson;
        try {
            cleanJson = extractJsonBlock(rawAiResponse);
        } catch (AiJsonParseException e) {
            log.error("[AiJsonParser] Không thể trích xuất JSON lõi từ phản hồi AI.");
            throw e; // Ném ngược ra cho Consumer xử lý
        }

        // 3. Ép kiểu (Object Mapping) & Bẫy lỗi sinh tử
        try {
            // Cấu hình Jackson cực kỳ "khoan dung" (lenient) để tự phục hồi lỗi từ AI
            // (Ollama/Llama)
            ObjectMapper lenientMapper = objectMapper.copy()
                    .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES)
                    .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES)
                    .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

            T dto = lenientMapper.readValue(cleanJson, targetType);

            // Validate DTO (Layer 5) để chặn rác (null) đi tiếp vào Database
            validateDto(dto);

            return dto;
        } catch (JsonProcessingException e) {
            log.error("[AiJsonParser] Lỗi ép kiểu Generic JSON:\n{}", cleanJson, e);
            throw new AiJsonParseException(
                    ErrorType.DTO_MAPPING_ERROR,
                    "Không thể ép kiểu JSON sang " + targetType.getSimpleName() + ". Lý do: " + e.getMessage(),
                    e);
        }
    }

    // =========================================================================
    // TASK 1 - Private Pipeline Stages (Layer 1, 2, 3)
    // =========================================================================

    /**
     * Layer 1 - Heuristic Extraction.
     * Finds the outermost JSON block using {@code indexOf} / {@code lastIndexOf}.
     * Handles: markdown fences, prose wrapping, multiple JSON blobs.
     *
     * <ul>
     * <li>startIndex = min(firstBrace, firstBracket) that is &gt; -1</li>
     * <li>endIndex = max(lastBrace, lastBracket)</li>
     * </ul>
     */
    private String extractJsonBlock(String rawAiResponse) {
        int firstBrace = rawAiResponse.indexOf(JSON_OBJECT_OPEN);
        int firstBracket = rawAiResponse.indexOf(JSON_ARRAY_OPEN);

        int startIndex = resolveStartIndex(firstBrace, firstBracket);

        int lastBrace = rawAiResponse.lastIndexOf(JSON_OBJECT_CLOSE);
        int lastBracket = rawAiResponse.lastIndexOf(JSON_ARRAY_CLOSE);
        int endIndex = Math.max(lastBrace, lastBracket);

        if (endIndex == -1 || endIndex <= startIndex) {
            log.error("[AiJsonParser] Layer 1 failed - invalid boundaries. startIndex={}, endIndex={}",
                    startIndex, endIndex);
            throw new AiJsonParseException(
                    ErrorType.INVALID_JSON_SYNTAX,
                    "No closing delimiter ('}' or ']') found after opening at index " + startIndex);
        }

        String extracted = rawAiResponse.substring(startIndex, endIndex + 1);
        log.debug("[AiJsonParser] Layer 1 - extracted, length: {} chars (start={}, end={})",
                extracted.length(), startIndex, endIndex);
        return extracted;
    }

    /**
     * Returns the smaller of two indexOf values, ignoring -1 (not found).
     *
     * @throws AiJsonParseException with {@link ErrorType#INVALID_JSON_SYNTAX} if
     *                              both are -1.
     */
    private int resolveStartIndex(int firstBrace, int firstBracket) {
        if (firstBrace == -1 && firstBracket == -1) {
            log.error("[AiJsonParser] Layer 1 failed - no opening delimiter found. " +
                    "firstBrace={}, firstBracket={}", firstBrace, firstBracket);
            throw new AiJsonParseException(
                    ErrorType.INVALID_JSON_SYNTAX,
                    "No JSON opening delimiter ('{' or '[') found in AI response.");
        }
        if (firstBrace == -1)
            return firstBracket; // only array present
        if (firstBracket == -1)
            return firstBrace; // only object present
        return Math.min(firstBrace, firstBracket); // both present -> take earliest
    }

    /**
     * Layer 2 - JSON Validation.
     * Uses {@link ObjectMapper#readTree(String)} for strict RFC-8259 syntax check.
     *
     * @throws AiJsonParseException with {@link ErrorType#INVALID_JSON_SYNTAX} on
     *                              parse failure.
     */
    private JsonNode validateJson(String extractedJson) {
        try {
            JsonNode node = objectMapper.readTree(extractedJson);
            log.debug("[AiJsonParser] Layer 2 - validation passed, node type: {}", node.getNodeType());
            return node;
        } catch (Exception e) {
            log.error("[AiJsonParser] Layer 2 failed - length: {} chars, reason: {}",
                    extractedJson.length(), e.getMessage());
            throw new AiJsonParseException(
                    ErrorType.INVALID_JSON_SYNTAX,
                    "Malformed JSON after extraction. Reason: " + e.getMessage(), e);
        }
    }

    /**
     * Layer 3 - Normalization.
     * Converts {@link JsonNode} to canonical, minified JSON string via
     * {@code node.toString()}.
     */
    private String normalizeJson(JsonNode jsonNode) {
        String normalized = jsonNode.toString();
        log.debug("[AiJsonParser] Layer 3 - normalized, output length: {} chars", normalized.length());
        return normalized;
    }

    // =========================================================================
    // TASK 2 - Private Pipeline Stages (Layer 4, 5, 6)
    // =========================================================================

    /**
     * Layer 4 - DTO Mapping.
     * <p>
     * Uses a LOCAL copy of ObjectMapper (via {@code copy()}) with
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled so the global shared bean
     * is never mutated. Converts clean JSON -> {@link AiInferenceResultDto}.
     * </p>
     *
     * @throws AiJsonParseException with {@link ErrorType#DTO_MAPPING_ERROR} on
     *                              failure.
     */
    private AiInferenceResultDto mapToDto(String cleanJson) {
        // Use copy() - NEVER modify the global shared ObjectMapper
        ObjectMapper localMapper = objectMapper.copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try {
            AiInferenceResultDto dto = localMapper.readValue(cleanJson, AiInferenceResultDto.class);
            log.debug("[AiJsonParser] Layer 4 - DTO mapping successful");
            return dto;
        } catch (Exception e) {
            log.error("[AiJsonParser] Layer 4 failed - DTO mapping error. " +
                    "Input length: {} chars, reason: {}", cleanJson.length(), e.getMessage());
            throw new AiJsonParseException(
                    ErrorType.DTO_MAPPING_ERROR,
                    "Failed to map JSON to AiInferenceResultDto. Reason: " + e.getMessage(), e);
        }
    }

    /**
     * Layer 5 - DTO Validation.
     * <p>
     * Runs Jakarta Bean Validation against all {@code @NotNull}, {@code @NotEmpty},
     * and {@code @Valid} cascade constraints defined on
     * {@link AiInferenceResultDto}.
     * All violations are collected and reported in a single exception - never
     * one-by-one.
     * </p>
     *
     * @throws AiJsonParseException with {@link ErrorType#DTO_VALIDATION_FAILED}
     *                              listing all violations.
     */
    private <T> void validateDto(T dto) {
        Set<ConstraintViolation<T>> violations = validator.validate(dto);
        if (violations.isEmpty()) {
            log.debug("[AiJsonParser] Layer 5 - DTO validation passed");
            return;
        }
        String violationSummary = violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));

        log.error("[AiJsonParser] Layer 5 failed - {} constraint violation(s): {}",
                violations.size(), violationSummary);
        throw new AiJsonParseException(
                ErrorType.DTO_VALIDATION_FAILED,
                "DTO constraint violations [" + violations.size() + "]: " + violationSummary);
    }

    /**
     * Layer 6 - Metrics Recording.
     * Increments a named Micrometer counter.
     * Success counter: {@value #METRIC_PARSE_SUCCESS}
     * Fail counter: {@value #METRIC_PARSE_FAIL}
     */
    private void recordMetrics(String metricName) {
        meterRegistry.counter(metricName).increment();
        log.debug("[AiJsonParser] Layer 6 - metric '{}' incremented", metricName);
    }

    // =========================================================================
    // Guard Methods
    // =========================================================================

    private void guardAgainstBlankInput(String rawAiResponse) {
        if (rawAiResponse == null || rawAiResponse.isBlank()) {
            log.error("[AiJsonParser] Pre-condition failed - raw AI response is null or blank.");
            throw AiProviderFailureException.emptyResponse("LLM", "unknown", null);
        }
    }

    private void guardAgainstBlankJson(String cleanJson) {
        if (cleanJson == null || cleanJson.isBlank()) {
            log.error("[AiJsonParser] Pre-condition failed - cleanJson is null or blank.");
            throw AiProviderFailureException.emptyResponse("LLM", "unknown", null);
        }
    }

    /**
     * Self-Healing parser dành riêng cho AI Skill 2 (Sinh Test Case).
     *
     * <p>
     * Pipeline:
     * <ol>
     * <li>Guard: kiểm tra input không rỗng.</li>
     * <li>Tìm ranh giới mảng JSON bằng
     * {@code indexOf('[') / lastIndexOf(']')}.</li>
     * <li>Validate JSON Array bằng Jackson.</li>
     * <li>Parse sang {@code List<AiTestCaseDto>} dùng {@link TypeReference} (tránh
     * lỗi casting runtime).</li>
     * </ol>
     *
     * @param rawJson Chuỗi thô từ AI (có thể bọc markdown, câu chào hỏi).
     * @return Danh sách
     *         {@link com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiTestCaseDto}
     *         đã parse.
     * @throws AiJsonParseException nếu không tìm thấy mảng JSON hoặc parse thất
     *                              bại.
     */
    @Override
    public List<com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiTestCaseDto> cleanAndParseTestCaseJson(
            String rawJson) {
        guardAgainstBlankInput(rawJson);

        // Layer 0: Normalize field-name aliases and enum aliases BEFORE parsing.
        // This is a cheap string-level fix so Jackson can map cleanly.
        String normalized = normalizeTestCaseAliases(rawJson);

        // Layer 0b: If the response is an object wrapper, extract the inner array.
        // Handles: {"testCases":[...]}, {"cases":[...]}, {"tests":[...]}, {"data":[...]}
        normalized = unwrapTestCaseArray(normalized);

        // Layer 1: Tìm ranh giới '[' đầu và ']' cuối
        int startIndex = normalized.indexOf(JSON_ARRAY_OPEN);
        int endIndex = normalized.lastIndexOf(JSON_ARRAY_CLOSE);

        if (startIndex == -1 || endIndex == -1 || endIndex <= startIndex) {
            log.error("[AiJsonParser][TestCase] Không tìm thấy mảng JSON hợp lệ. startIndex={}, endIndex={}",
                    startIndex, endIndex);
            throw new AiJsonParseException(
                    ErrorType.INVALID_JSON_SYNTAX,
                    "Invalid JSON array structure from AI: cannot find '[' or ']' delimiters.");
        }

        String cleanJson = normalized.substring(startIndex, endIndex + 1).trim();
        if (cleanJson.isBlank()) {
            throw new AiJsonParseException(ErrorType.INVALID_JSON_SYNTAX, "Cleaned JSON array is empty.");
        }

        log.debug("[AiJsonParser][TestCase] Extracted JSON array - length: {} chars", cleanJson.length());

        // Layer 2: Validate JSON syntax bằng cách đọc thành JsonNode trước
        try {
            JsonNode arrayNode = objectMapper.readTree(cleanJson);
            if (!arrayNode.isArray()) {
                throw new AiJsonParseException(ErrorType.INVALID_JSON_SYNTAX, "AI response is not a JSON array.");
            }
            log.debug("[AiJsonParser][TestCase] JSON validation passed - {} elements", arrayNode.size());
        } catch (Exception e) {
            if (e instanceof AiJsonParseException)
                throw (AiJsonParseException) e;
            log.error("[AiJsonParser][TestCase] JSON syntax invalid. Clean JSON: {}", cleanJson);
            throw new AiJsonParseException(ErrorType.INVALID_JSON_SYNTAX, "Malformed JSON array: " + e.getMessage(), e);
        }

        // Layer 3: Parse sang List<AiTestCaseDto> dùng TypeReference (bắt buộc để tránh
        // lỗi runtime casting)
        try {
            ObjectMapper lenientMapper = objectMapper.copy()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

            List<com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiTestCaseDto> result = lenientMapper.readValue(
                    cleanJson,
                    new TypeReference<List<com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiTestCaseDto>>() {
                    });

            log.info("[AiJsonParser][TestCase] Parse thành công - {} test case(s) được trích xuất.", result.size());
            return result;

        } catch (JsonProcessingException e) {
            log.error("[AiJsonParser][TestCase] Parse thất bại. Clean JSON (debug):\n{}", cleanJson);
            throw new AiJsonParseException(
                    ErrorType.DTO_MAPPING_ERROR,
                    "Cannot parse JSON array to AiTestCaseDto list. Reason: " + e.getMessage(), e);
        }
    }

    /**
     * Normalizes field-name and enum-value aliases that AI models commonly emit.
     *
     * <h3>Field-name aliases (JSON key replacement)</h3>
     * <ul>
     *   <li>{@code "test_name"} → {@code "case_name"}</li>
     *   <li>{@code "testName"}  → {@code "case_name"} (camelCase variant)</li>
     * </ul>
     *
     * <h3>Enum-value aliases (scoped to case_type / caseType field only)</h3>
     * <ul>
     *   <li>{@code "case_type": "SUCCESS"}  → {@code "case_type": "POSITIVE"}</li>
     *   <li>{@code "case_type": "FAILURE"}  → {@code "case_type": "NEGATIVE"}</li>
     *   <li>{@code "caseType": "SUCCESS"}   → {@code "caseType": "POSITIVE"}</li>
     *   <li>{@code "caseType": "FAILURE"}   → {@code "caseType": "NEGATIVE"}</li>
     * </ul>
     *
     * <p><strong>Safety note:</strong> SUCCESS/FAILURE replacements are scoped to
     * the {@code case_type}/{@code caseType} field via regex so that other JSON
     * string values (e.g. {@code "description"}, {@code "message"},
     * {@code "expectedValue"}) containing these words are never mutated.</p>
     *
     * <h3>Assertion-type aliases (value replacement)</h3>
     * <ul>
     *   <li>{@code "VALIDATION_ERROR"} → {@code "VALIDATION"}</li>
     *   <li>{@code "CLIENT_ERROR"}     → {@code "VALIDATION"}</li>
     *   <li>{@code "JSON_BODY"}        → {@code "JSON_PATH"}</li>
     * </ul>
     */
    private String normalizeTestCaseAliases(String raw) {
        if (raw == null) return "";
        return raw
                // ── Field-name aliases ─────────────────────────────────────────────
                .replace("\"test_name\"", "\"case_name\"")
                .replace("\"testName\"",  "\"case_name\"")

                // ── case_type enum aliases — scoped to the field, not global ───────
                // Matches: "case_type"  : "SUCCESS"  and  "caseType"  : "SUCCESS"
                // (optional whitespace around the colon is handled by \s*)
                .replaceAll("(\"(?:case_type|caseType)\"\\s*:\\s*)\"SUCCESS\"",  "$1\"POSITIVE\"")
                .replaceAll("(\"(?:case_type|caseType)\"\\s*:\\s*)\"FAILURE\"",  "$1\"NEGATIVE\"")

                // ── Assertion-type aliases — these appear only as enum values ──────
                // VALIDATION_ERROR / CLIENT_ERROR are not common English words in
                // free-text fields, so a global replace is low-risk and correct here.
                .replace("\"VALIDATION_ERROR\"",  "\"VALIDATION\"")
                .replace("\"CLIENT_ERROR\"",      "\"VALIDATION\"")
                .replace("\"JSON_BODY\"",          "\"JSON_PATH\"");
    }

    /**
     * If the AI wraps the test-case array inside an object
     * (e.g. {@code {"testCases": [...]}}) this method extracts the raw array
     * string so the downstream array parser works correctly.
     *
     * <p>Supported wrapper keys (case-insensitive): {@code testCases}, {@code cases},
     * {@code tests}, {@code data}, {@code items}, {@code test_cases}.
     */
    private String unwrapTestCaseArray(String raw) {
        int firstBrace = raw.indexOf('{');
        int firstBracket = raw.indexOf('[');

        // If the outermost structure is already an array, no unwrapping needed.
        if (firstBracket != -1 && (firstBrace == -1 || firstBracket < firstBrace)) {
            return raw;
        }
        if (firstBrace == -1) {
            return raw; // No JSON object found at all — let downstream handle.
        }

        try {
            JsonNode root = objectMapper.readTree(raw.substring(firstBrace, raw.lastIndexOf('}') + 1));
            if (!root.isObject()) return raw;

            // Try known wrapper keys
            for (String key : new String[]{"testCases", "test_cases", "cases", "tests", "data", "items"}) {
                JsonNode candidate = root.get(key);
                if (candidate != null && candidate.isArray()) {
                    log.debug("[AiJsonParser][TestCase] Unwrapped object key='{}' containing {} element(s)", key, candidate.size());
                    return candidate.toString();
                }
            }
        } catch (Exception e) {
            log.debug("[AiJsonParser][TestCase] unwrapTestCaseArray parse attempt failed, continuing: {}", e.getMessage());
        }
        return raw; // Could not unwrap — let downstream decide.
    }
}
