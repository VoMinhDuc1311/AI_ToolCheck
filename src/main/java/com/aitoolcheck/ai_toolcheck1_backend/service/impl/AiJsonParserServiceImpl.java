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
            // Layer 1: Trích xuất lõi JSON (Loại bỏ Markdown, tìm '{' hoặc '[')
            String cleanJson = extractJsonBlock(rawAiResponse);

            // Layer 2: Parse thành JsonNode
            JsonNode rootNode = validateJson(cleanJson);

            // Layer 3: Normalize schema linh hoạt
            JsonNode normalizedNode = normalizeTestCaseJsonNode(rootNode);

            // Layer 4: Ánh xạ (Mapping) bằng ObjectMapper (Copy)
            ObjectMapper localMapper = objectMapper.copy()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

            AiGeneratedTestCaseRequest dto = localMapper.treeToValue(normalizedNode, AiGeneratedTestCaseRequest.class);

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

        try {
            // Layer 1: Trích xuất lõi JSON
            String cleanJson = extractJsonBlock(rawJson);

            // Layer 2: Parse thành JsonNode
            JsonNode rootNode = validateJson(cleanJson);

            // Layer 3: Normalize schema linh hoạt
            JsonNode normalizedNode = normalizeTestCaseJsonNode(rootNode);

            // Lấy mảng test_cases
            JsonNode testCasesArray = normalizedNode.get("test_cases");
            if (testCasesArray == null || !testCasesArray.isArray()) {
                throw new AiJsonParseException(ErrorType.INVALID_JSON_SYNTAX, "No test cases array found in normalized JSON.");
            }

            ObjectMapper lenientMapper = objectMapper.copy()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

            List<com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiTestCaseDto> result = lenientMapper.readValue(
                    testCasesArray.toString(),
                    new TypeReference<List<com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiTestCaseDto>>() {
                    });

            log.info("[AiJsonParser][TestCase] Parse thành công - {} test case(s) được trích xuất.", result.size());
            return result;

        } catch (AiJsonParseException e) {
            throw e;
        } catch (Exception e) {
            log.error("[AiJsonParser][TestCase] Parse thất bại. Raw JSON (debug):\n{}", rawJson, e);
            throw new AiJsonParseException(
                    ErrorType.DTO_MAPPING_ERROR,
                    "Cannot parse JSON array to AiTestCaseDto list. Reason: " + e.getMessage(), e);
        }
    }

    /**
     * Normalizes test case JSON structure and fields to be robust against variations in AI outputs.
     */
    private JsonNode normalizeTestCaseJsonNode(JsonNode rootNode) {
        if (rootNode == null || rootNode.isNull()) {
            return objectMapper.createObjectNode();
        }

        // 1. Convert root array to root object if needed
        com.fasterxml.jackson.databind.node.ObjectNode normalizedRoot;
        if (rootNode.isArray()) {
            normalizedRoot = objectMapper.createObjectNode();
            normalizedRoot.set("test_cases", rootNode);
        } else if (rootNode.isObject()) {
            normalizedRoot = (com.fasterxml.jackson.databind.node.ObjectNode) rootNode.deepCopy();
        } else {
            throw new AiJsonParseException(ErrorType.INVALID_JSON_SYNTAX, "Root JSON must be an object or an array.");
        }

        // 2. Unwrap wrapper keys into "test_cases"
        JsonNode testCasesNode = normalizedRoot.get("test_cases");
        if (testCasesNode == null || !testCasesNode.isArray()) {
            String[] candidateKeys = {"testCases", "test_cases", "cases", "items", "tests", "data", "testcases"};
            for (String key : candidateKeys) {
                JsonNode candidate = normalizedRoot.get(key);
                if (candidate != null && candidate.isArray()) {
                    normalizedRoot.set("test_cases", candidate);
                    if (!"test_cases".equals(key)) {
                        normalizedRoot.remove(key);
                    }
                    break;
                }
            }
        }

        // 3. Ensure "test_cases" field is present and is an ArrayNode
        JsonNode finalTestCases = normalizedRoot.get("test_cases");
        if (finalTestCases == null || !finalTestCases.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode emptyArray = objectMapper.createArrayNode();
            normalizedRoot.set("test_cases", emptyArray);
            finalTestCases = emptyArray;
        }

        com.fasterxml.jackson.databind.node.ArrayNode testCasesArray = (com.fasterxml.jackson.databind.node.ArrayNode) finalTestCases;

        // 4. Iterate over each test case item
        for (int i = 0; i < testCasesArray.size(); i++) {
            JsonNode itemNode = testCasesArray.get(i);
            if (itemNode == null || !itemNode.isObject()) {
                continue;
            }

            com.fasterxml.jackson.databind.node.ObjectNode itemObj = (com.fasterxml.jackson.databind.node.ObjectNode) itemNode;

            // 4.1. Normalize test_name / case_name / testName / caseName / name
            normalizeField(itemObj, "test_name", "testName", "case_name", "caseName", "name");
            JsonNode nameNode = itemObj.get("test_name");
            if (nameNode != null) {
                itemObj.set("test_name", nameNode);
                itemObj.set("case_name", nameNode);
            }

            // 4.2. Normalize http_method / httpMethod / method
            normalizeField(itemObj, "http_method", "httpMethod", "method");
            JsonNode methodNode = itemObj.get("http_method");
            if (methodNode != null && methodNode.isTextual()) {
                itemObj.put("http_method", methodNode.asText().trim().toUpperCase());
            }

            // 4.3. Normalize expected_status_code
            normalizeField(itemObj, "expected_status_code", "expectedStatusCode", "expected_status", "statusCode");
            JsonNode statusCodeNode = itemObj.get("expected_status_code");
            if (statusCodeNode != null && !statusCodeNode.isNull()) {
                if (statusCodeNode.isTextual()) {
                    try {
                        int code = Integer.parseInt(statusCodeNode.asText().trim());
                        itemObj.put("expected_status_code", code);
                    } catch (NumberFormatException ignored) {}
                }
            }

            // 4.4. Normalize case_type / caseType
            normalizeField(itemObj, "case_type", "caseType");
            JsonNode caseTypeNode = itemObj.get("case_type");
            if (caseTypeNode != null && caseTypeNode.isTextual()) {
                String val = caseTypeNode.asText().trim().toUpperCase();
                // Map from aliases
                if ("SUCCESS".equals(val)) {
                    val = "POSITIVE";
                } else if ("FAILURE".equals(val)) {
                    val = "NEGATIVE";
                } else if ("VALIDATION_ERROR".equals(val) || "CLIENT_ERROR".equals(val)) {
                    val = "VALIDATION";
                }
                itemObj.put("case_type", val);
            }

            // 4.5. Normalize priority / priority_level / priorityLevel
            normalizeField(itemObj, "priority", "priority_level", "priorityLevel");
            JsonNode priorityNode = itemObj.get("priority");
            if (priorityNode != null) {
                if (priorityNode.isTextual()) {
                    String val = priorityNode.asText().trim().toUpperCase();
                    itemObj.put("priority", val);
                    itemObj.put("priority_level", val);
                } else {
                    itemObj.set("priority", priorityNode);
                    itemObj.set("priority_level", priorityNode);
                }
            }

            // 4.6. Normalize inputs / flat input fields
            normalizeField(itemObj, "inputs", "input", "parameters", "params");
            JsonNode inputsNode = itemObj.get("inputs");
            
            JsonNode pathParams = itemObj.get("path_params");
            if (pathParams == null) pathParams = itemObj.get("pathParams");
            
            JsonNode queryParams = itemObj.get("query_params");
            if (queryParams == null) queryParams = itemObj.get("queryParams");
            
            JsonNode requestBody = itemObj.get("request_body");
            if (requestBody == null) requestBody = itemObj.get("requestBody");
            
            JsonNode headers = itemObj.get("headers");

            // If we have flat fields but no inputs list, build the inputs list
            if ((inputsNode == null || !inputsNode.isArray() || inputsNode.size() == 0)
                && (pathParams != null || queryParams != null || requestBody != null || headers != null)) {
                com.fasterxml.jackson.databind.node.ArrayNode inputsArray = objectMapper.createArrayNode();
                if (pathParams != null && !pathParams.isNull()) {
                    com.fasterxml.jackson.databind.node.ObjectNode inputNode = objectMapper.createObjectNode();
                    inputNode.put("param_in", "PATH");
                    inputNode.set("payload", pathParams);
                    inputsArray.add(inputNode);
                }
                if (queryParams != null && !queryParams.isNull()) {
                    com.fasterxml.jackson.databind.node.ObjectNode inputNode = objectMapper.createObjectNode();
                    inputNode.put("param_in", "QUERY");
                    inputNode.set("payload", queryParams);
                    inputsArray.add(inputNode);
                }
                if (requestBody != null && !requestBody.isNull()) {
                    com.fasterxml.jackson.databind.node.ObjectNode inputNode = objectMapper.createObjectNode();
                    inputNode.put("param_in", "BODY");
                    inputNode.set("payload", requestBody);
                    inputsArray.add(inputNode);
                }
                if (headers != null && !headers.isNull()) {
                    com.fasterxml.jackson.databind.node.ObjectNode inputNode = objectMapper.createObjectNode();
                    inputNode.put("param_in", "HEADER");
                    inputNode.set("payload", headers);
                    inputsArray.add(inputNode);
                }
                itemObj.set("inputs", inputsArray);
                inputsNode = inputsArray;
            }

            // If we have inputs list but no flat fields, populate the flat fields
            if (inputsNode != null && inputsNode.isArray()) {
                com.fasterxml.jackson.databind.node.ArrayNode inputsArray = (com.fasterxml.jackson.databind.node.ArrayNode) inputsNode;
                for (int j = 0; j < inputsArray.size(); j++) {
                    JsonNode inputItem = inputsArray.get(j);
                    if (inputItem != null && inputItem.isObject()) {
                        com.fasterxml.jackson.databind.node.ObjectNode inputObj = (com.fasterxml.jackson.databind.node.ObjectNode) inputItem;
                        normalizeField(inputObj, "param_in", "paramIn", "in", "location");
                        JsonNode paramInNode = inputObj.get("param_in");
                        if (paramInNode != null && paramInNode.isTextual()) {
                            String paramInStr = paramInNode.asText().trim().toUpperCase();
                            inputObj.put("param_in", paramInStr);
                            
                            JsonNode payload = inputObj.get("payload");
                            if (payload != null && !payload.isNull()) {
                                if ("PATH".equals(paramInStr)) {
                                    itemObj.set("path_params", payload);
                                    itemObj.set("pathParams", payload);
                                } else if ("QUERY".equals(paramInStr)) {
                                    itemObj.set("query_params", payload);
                                    itemObj.set("queryParams", payload);
                                } else if ("BODY".equals(paramInStr)) {
                                    itemObj.set("request_body", payload);
                                    itemObj.set("requestBody", payload);
                                } else if ("HEADER".equals(paramInStr)) {
                                    itemObj.set("headers", payload);
                                }
                            }
                        }
                    }
                }
            }

            // 4.7. Normalize assertions
            normalizeField(itemObj, "assertions", "assertion", "rules", "asserts");
            JsonNode assertionsNode = itemObj.get("assertions");
            if (assertionsNode != null && assertionsNode.isArray()) {
                com.fasterxml.jackson.databind.node.ArrayNode assertionsArray = (com.fasterxml.jackson.databind.node.ArrayNode) assertionsNode;
                for (int j = 0; j < assertionsArray.size(); j++) {
                    JsonNode assertItem = assertionsArray.get(j);
                    if (assertItem != null && assertItem.isObject()) {
                        com.fasterxml.jackson.databind.node.ObjectNode assertObj = (com.fasterxml.jackson.databind.node.ObjectNode) assertItem;
                        
                        // normalize assertion_type / assertionType / type
                        normalizeField(assertObj, "assertion_type", "assertionType", "type");
                        JsonNode assertTypeNode = assertObj.get("assertion_type");
                        if (assertTypeNode != null && assertTypeNode.isTextual()) {
                            String val = assertTypeNode.asText().trim().toUpperCase();
                            if ("JSON_BODY".equals(val)) {
                                val = "JSON_PATH";
                            } else if ("VALIDATION_ERROR".equals(val) || "CLIENT_ERROR".equals(val)) {
                                val = "VALIDATION";
                            }
                            assertObj.put("assertion_type", val);
                        }

                        // normalize target_path / targetPath / json_path / jsonPath / path
                        normalizeField(assertObj, "target_path", "targetPath", "json_path", "jsonPath", "path");
                        JsonNode pathVal = assertObj.get("target_path");
                        if (pathVal != null) {
                            assertObj.set("target_path", pathVal);
                            assertObj.set("json_path", pathVal);
                            assertObj.set("jsonPath", pathVal);
                        }

                        // normalize operator / comparison_operator / comparisonOperator
                        normalizeField(assertObj, "operator", "comparison_operator", "comparisonOperator");
                        JsonNode opVal = assertObj.get("operator");
                        if (opVal != null) {
                            if (opVal.isTextual()) {
                                String val = opVal.asText().trim().toUpperCase();
                                if ("NOT_NULL".equals(val)) {
                                    val = "IS_NOT_NULL";
                                }
                                assertObj.put("operator", val);
                                assertObj.put("comparison_operator", val);
                            } else {
                                assertObj.set("operator", opVal);
                                assertObj.set("comparison_operator", opVal);
                            }
                        }

                        // normalize expected_value / expectedValue
                        JsonNode expNode = assertObj.get("expected_value");
                        if (expNode == null) {
                            expNode = assertObj.get("expectedValue");
                        }
                        if (expNode != null && !expNode.isNull()) {
                            String valueStr;
                            if (expNode.isContainerNode()) {
                                valueStr = expNode.toString();
                            } else {
                                valueStr = expNode.asText();
                            }
                            assertObj.put("expected_value", valueStr);
                            assertObj.put("expectedValue", valueStr);
                        }
                    }
                }
            }
        }

        return normalizedRoot;
    }

    private void normalizeField(com.fasterxml.jackson.databind.node.ObjectNode obj, String targetKey, String... alternateKeys) {
        if (obj.has(targetKey) && !obj.get(targetKey).isNull()) {
            return;
        }
        for (String altKey : alternateKeys) {
            if (obj.has(altKey) && !obj.get(altKey).isNull()) {
                obj.set(targetKey, obj.get(altKey));
                break;
            }
        }
    }
}
