package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiInferenceResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException.ErrorType;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJsonParserService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;

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
    public AiInferenceResultDto parseToDto(String cleanJson) {
        guardAgainstBlankJson(cleanJson);

        log.debug("[AiJsonParser] Starting deserialization pipeline - input length: {} chars", cleanJson.length());

        try {
            AiInferenceResultDto dto = mapToDto(cleanJson); // Layer 4: Mapping
            validateDto(dto); // Layer 5: Validation
            recordMetrics(METRIC_PARSE_SUCCESS); // Layer 6: Metrics (success)

            log.info("[AiJsonParser] Parse success - {} endpoint(s) extracted",
                    dto.getEndpoints().size());
            return dto;

        } catch (AiJsonParseException e) {
            recordMetrics(METRIC_PARSE_FAIL); // Layer 6: Metrics (fail)
            log.error("[AiJsonParser] Parse failed - errorType: {}, reason: {}",
                    e.getErrorType(), e.getMessage());
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
            // ObjectMapper sẽ đọc cleanJson và ép sang targetType.
            // Các DTO như AiDocumentEnrichmentResponseDto đã có @JsonIgnoreProperties(ignoreUnknown = true)
            // nên không sợ lỗi UnrecognizedPropertyException.
            T dto = objectMapper.readValue(cleanJson, targetType);
            
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
            throw new AiJsonParseException(
                    ErrorType.INVALID_JSON_SYNTAX,
                    "Raw AI response must not be null or blank.");
        }
    }

    private void guardAgainstBlankJson(String cleanJson) {
        if (cleanJson == null || cleanJson.isBlank()) {
            log.error("[AiJsonParser] Pre-condition failed - cleanJson is null or blank.");
            throw new AiJsonParseException(
                    ErrorType.INVALID_JSON_SYNTAX,
                    "Clean JSON input must not be null or blank.");
        }
    }
}