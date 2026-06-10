package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiDocumentEnrichmentResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException.ErrorType;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJsonParserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiModelRouterService;
import com.aitoolcheck.ai_toolcheck1_backend.service.DocumentEnrichmentService;
import com.aitoolcheck.ai_toolcheck1_backend.service.GeminiApiClientService;
import com.aitoolcheck.ai_toolcheck1_backend.service.VectorSearchService;
import com.aitoolcheck.ai_toolcheck1_backend.service.ai.AiPromptConstants;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiPayloadOptimizerService;
import com.aitoolcheck.ai_toolcheck1_backend.config.properties.AiOptimizationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Triển khai luồng AI Skill 1 — Làm giàu tài liệu API (Document Enrichment).
 *
 * <p>
 * <b>Luồng mới (v2 — Multi-Model + RAG):</b>
 * <ol>
 * <li>Gọi {@link VectorSearchService} để tìm các ví dụ tương tự trong Vector
 * Store (RAG Retrieval).</li>
 * <li>Ghép RAG context vào Prompt chuẩn
 * ({@link AiPromptConstants#ENRICH_DOC_SYSTEM_PROMPT}).</li>
 * <li>Gọi {@link AiModelRouterService#executeWithFallback(String)} — Router tự
 * quyết định
 * Ollama Tier1 → Ollama Tier2 → Gemini Cloud.</li>
 * <li>Đưa raw text qua {@link AiJsonParserService#parseJson} để ép kiểu sang
 * DTO.</li>
 * <li>Lưu embedding của nội dung vừa enrich vào Vector Store để làm giàu RAG
 * context tương lai.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentEnrichmentServiceImpl implements DocumentEnrichmentService {

    private final AiModelRouterService aiModelRouterService;
    private final VectorSearchService vectorSearchService;
    private final AiJsonParserService aiJsonParserService;
    private final GeminiApiClientService geminiApiClientService;
    private final AiPayloadOptimizerService aiPayloadOptimizerService;
    private final AiOptimizationProperties aiOptimizationProperties;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // PRIMARY ENTRY POINT (Multi-Model + RAG)
    // =========================================================================

    @Override
    // THAY ĐỔI: Thêm tham số apiEndpointId vào hàm (20/05/2026)
    public AiDocumentEnrichmentResponseDto enrichDocumentation(String apiEndpointId, String openApiFragment) {
        log.info("[DocumentEnrichment] Bắt đầu luồng RAG + Multi-Model Enrichment cho Endpoint ID: {}", apiEndpointId);

        if (openApiFragment == null || openApiFragment.isBlank()) {
            throw new IllegalArgumentException("[DocumentEnrichment] openApiFragment không được để trống.");
        }

        String optimizedFragment = aiOptimizationProperties.isEnabled()
                ? aiPayloadOptimizerService.truncateIfNeeded(openApiFragment,
                        aiOptimizationProperties.getMaxPromptChars() / 2)
                : openApiFragment;

        // ── Bước 1: RAG Retrieval — Tìm context tương tự từ Vector Store ──────
        log.info("[DocumentEnrichment] Bước 1: Tìm RAG context liên quan...");
        String ragContext = vectorSearchService.findSimilarContext(optimizedFragment, 3);

        if (ragContext.isBlank()) {
            log.info("[DocumentEnrichment] Không có RAG context (Vector Store trống hoặc không tìm thấy kết quả).");
        } else {
            log.info("[DocumentEnrichment] Đã lấy RAG context — {} chars.", ragContext.length());
        }

        // ── Bước 2: Build Prompt với RAG Context ─────────────────────────────
        String geminiPrompt = String.format(AiPromptConstants.ENRICH_DOC_SYSTEM_PROMPT,
                ragContext, optimizedFragment);
        String ollamaPrompt = buildOllamaEnrichPrompt(optimizedFragment);

        log.info("[DocumentEnrichment] Bước 2: Đã build prompts — Gemini {} chars, Ollama {} chars.",
                geminiPrompt.length(), ollamaPrompt.length());

        // ── Bước 3: Gọi Router (Gemini Cloud → Ollama local) ─────────
        log.info("[DocumentEnrichment] Bước 3: Gọi AiModelRouterService...");
        String rawAiText = aiModelRouterService.executeWithFallbackForSkill(
                "enrich_api_doc",
                () -> geminiPrompt,
                () -> ollamaPrompt,
                raw -> aiJsonParserService.parseJson(raw, AiDocumentEnrichmentResponseDto.class));
        log.info("[DocumentEnrichment] Bước 3: Router trả về {} chars.", rawAiText.length());

        // ── Bước 4: Parse JSON → DTO ─────────────────────────────────────────
        log.info("[DocumentEnrichment] Bước 4: Đưa vào AiJsonParserService để ép kiểu...");
        AiDocumentEnrichmentResponseDto resultDto = aiJsonParserService.parseJson(
                rawAiText, AiDocumentEnrichmentResponseDto.class);

        // ── Bước 5: Lưu Embedding vào Vector Store (Chống Rác + Gắn ID) ────────
        // THAY ĐỔI: Kiểm tra nếu summary rỗng hoặc quá ngắn thì không lưu để tránh làm
        // "ngu" AI
        if (resultDto.getSummary() != null && !resultDto.getSummary().trim().isEmpty()) {
            String contentToStore = "API Metadata:\n" + optimizedFragment
                    + "\n\nAI Summary:\n" + resultDto.getSummary();

            // THAY ĐỔI: Truyền apiEndpointId thay vì chữ null
            vectorSearchService.storeEmbedding(apiEndpointId, contentToStore);
            log.info("[DocumentEnrichment] Bước 5: Đã lưu embedding mới vào Vector Store (source_id: {}).",
                    apiEndpointId);
        } else {
            log.warn("[DocumentEnrichment] Bước 5: Bỏ qua lưu Vector vì nội dung AI sinh ra rỗng (Tránh rác DB).");
        }

        log.info("[DocumentEnrichment] Hoàn thành xuất sắc! summary: \"{}\"",
                resultDto.getSummary() != null ? resultDto.getSummary().substring(0,
                        Math.min(80, resultDto.getSummary().length())) + "..." : "null");

        return resultDto;
    }

    String buildOllamaEnrichPrompt(String openApiFragment) {
        EndpointPromptContext context = extractEndpointPromptContext(openApiFragment);
        String compactMetadata = endpointContextJson(context);

        StringBuilder prompt = new StringBuilder(2200);
        prompt.append("Task: Enrich one API endpoint for documentation.\n");
        prompt.append("Return ONLY one valid JSON object. No markdown. No prose. No ```json fences.\n\n");
        prompt.append("Endpoint facts:\n");
        prompt.append("- HTTP method: ").append(context.method()).append('\n');
        prompt.append("- Path: ").append(context.path()).append('\n');
        prompt.append("- Controller/Class: ").append(context.controller()).append('\n');
        prompt.append("- Operation/Method: ").append(context.operation()).append('\n');
        prompt.append("- Path params: ").append(context.pathParams()).append('\n');
        prompt.append("- Query params: ").append(context.queryParams()).append('\n');
        prompt.append("- Request body fields: ").append(context.requestFields()).append('\n');
        prompt.append("- Response fields: ").append(context.responseFields()).append("\n\n");
        prompt.append("Source metadata JSON:\n");
        prompt.append(limit(compactMetadata, 1200)).append("\n\n");
        prompt.append("Output schema exactly:\n");
        prompt.append("{\"summary\":\"short title <=100 chars\",");
        prompt.append("\"description\":\"clear API description grounded only in endpoint facts\",");
        prompt.append("\"example_request_json\":{},");
        prompt.append("\"example_response_json\":{},");
        prompt.append("\"openapi_fragment_json\":{}}\n");
        prompt.append("Use only fields present in Source metadata JSON. Do not invent fields.");

        return limit(prompt.toString(), 3000);
    }

    private EndpointPromptContext extractEndpointPromptContext(String openApiFragment) {
        try {
            JsonNode root = objectMapper.readTree(openApiFragment);
            JsonNode operationNode = root;
            String path = firstText(root, "path", "endpointPath", "uri");
            String method = firstText(root, "method", "httpMethod");

            if (root.isObject()) {
                for (java.util.Iterator<String> pathNames = root.fieldNames(); pathNames.hasNext();) {
                    String candidatePath = pathNames.next();
                    JsonNode pathNode = root.get(candidatePath);
                    if (candidatePath.startsWith("/") && pathNode != null && pathNode.isObject()) {
                        path = candidatePath;
                        for (String candidateMethod : java.util.List.of(
                                "get", "post", "put", "patch", "delete", "head", "options")) {
                            JsonNode candidateOperation = pathNode.get(candidateMethod);
                            if (candidateOperation != null && candidateOperation.isObject()) {
                                method = candidateMethod.toUpperCase(java.util.Locale.ROOT);
                                operationNode = candidateOperation;
                                break;
                            }
                        }
                        break;
                    }
                }
            }

            if (method == null || method.isBlank()) {
                method = "unknown";
            }
            if (path == null || path.isBlank()) {
                path = "unknown";
            }

            String controller = firstText(operationNode, "controllerName", "controller", "className", "resourceClass");
            String operation = firstText(operationNode, "operationId", "methodName", "handlerMethod", "summary");
            return new EndpointPromptContext(
                    method,
                    path,
                    controller == null ? "unknown" : controller,
                    operation == null ? "unknown" : operation,
                    collectParameters(operationNode, "path"),
                    collectParameters(operationNode, "query"),
                    collectFields(operationNode, "request"),
                    collectFields(operationNode, "response"));
        } catch (Exception ex) {
            return new EndpointPromptContext(
                    "unknown",
                    "unknown",
                    "unknown",
                    "unknown",
                    "unknown",
                    "unknown",
                    "unknown",
                    "unknown");
        }
    }

    private String firstText(JsonNode node, String... fieldNames) {
        if (node == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private String collectParameters(JsonNode operationNode, String location) {
        JsonNode parameters = operationNode == null ? null : operationNode.get("parameters");
        if (parameters == null || !parameters.isArray()) {
            return "none";
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        for (JsonNode parameter : parameters) {
            String in = firstText(parameter, "in", "paramIn", "location");
            if (location.equalsIgnoreCase(in)) {
                String name = firstText(parameter, "name", "paramName");
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return names.isEmpty() ? "none" : limit(String.join(", ", names), 220);
    }

    private String collectFields(JsonNode operationNode, String prefix) {
        if (operationNode == null) {
            return "unknown";
        }
        java.util.List<String> fields = new java.util.ArrayList<>();
        if ("request".equals(prefix)) {
            collectFieldNames(operationNode.get("requestBody"), fields);
            collectFieldNames(operationNode.get("request"), fields);
        } else {
            collectFieldNames(operationNode.get("responses"), fields);
            collectFieldNames(operationNode.get("response"), fields);
        }
        return fields.isEmpty() ? "unknown" : limit(String.join(", ", fields), 260);
    }

    private void collectFieldNames(JsonNode node, java.util.List<String> fields) {
        if (node == null || fields.size() >= 20) {
            return;
        }
        if (node.isObject()) {
            JsonNode properties = node.get("properties");
            if (properties != null && properties.isObject()) {
                properties.fieldNames().forEachRemaining(field -> {
                    if (fields.size() < 20) {
                        fields.add(field);
                    }
                });
            }
            java.util.Iterator<JsonNode> children = node.elements();
            while (children.hasNext() && fields.size() < 20) {
                collectFieldNames(children.next(), fields);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectFieldNames(child, fields);
                if (fields.size() >= 20) {
                    break;
                }
            }
        }
    }

    private String endpointContextJson(EndpointPromptContext context) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "method", context.method(),
                    "path", context.path(),
                    "controller", context.controller(),
                    "operation", context.operation(),
                    "pathParams", context.pathParams(),
                    "queryParams", context.queryParams(),
                    "requestFields", context.requestFields(),
                    "responseFields", context.responseFields()));
        } catch (Exception ex) {
            return "";
        }
    }

    private String limit(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 15)) + "...[truncated]";
    }

    private record EndpointPromptContext(
            String method,
            String path,
            String controller,
            String operation,
            String pathParams,
            String queryParams,
            String requestFields,
            String responseFields) {
    }

    // =========================================================================
    // BACKWARD-COMPAT
    // =========================================================================

    @Override
    @Deprecated(since = "v2-MultiModelRAG", forRemoval = false)
    public GeminiResponse getRawGeminiResponse(String openApiFragment) {
        if (openApiFragment == null || openApiFragment.isBlank()) {
            throw new IllegalArgumentException("[DocumentEnrichment] Metadata đầu vào không được để trống.");
        }
        String finalPrompt = String.format(AiPromptConstants.ENRICH_DOC_SYSTEM_PROMPT,
                "", openApiFragment);
        log.debug("[DocumentEnrichment][LegacyGemini] Gọi Gemini trực tiếp. Prompt: {} chars",
                finalPrompt.length());
        return geminiApiClientService.getFullAiResponse(finalPrompt);
    }

    private String extractTextSafely(GeminiResponse response) {
        try {
            if (response == null || response.getCandidates() == null || response.getCandidates().isEmpty()) {
                throw new AiJsonParseException(ErrorType.INVALID_JSON_SYNTAX,
                        "Gemini trả về response rỗng hoặc không có candidate.");
            }
            return response.getCandidates().get(0)
                    .getContent().getParts().get(0)
                    .getText();
        } catch (NullPointerException | IndexOutOfBoundsException e) {
            log.error("[DocumentEnrichment] Không thể bóc tách text từ GeminiResponse.", e);
            throw new AiJsonParseException(ErrorType.INVALID_JSON_SYNTAX,
                    "Cấu trúc phản hồi từ Google bị lỗi/thiếu data.", e);
        }
    }
}
