package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.GeminiProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiInferenceResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiProviderFailureException;
import com.aitoolcheck.ai_toolcheck1_backend.service.GeminiApiClientService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class GeminiApiClientServiceImpl implements GeminiApiClientService {

        private final WebClient webClient;
        private final GeminiProperties geminiProperties;
        private final ObjectMapper objectMapper;
        private final AtomicReference<Instant> cooldownUntil = new AtomicReference<>(Instant.EPOCH);

        // Paste toàn bộ System Prompt siêu xịn của bạn vào đây
        private static final String LEGACY_EXTRACTOR_PROMPT = """
                        You are an Expert System Architect specializing in modernizing legacy Java applications.
                        Your task is to analyze raw legacy Java source code and extract all HTTP API endpoints into a strict, deterministic JSON format.

                        CRITICAL RULES:
                        1. LEGACY CONTEXT: The code lacks modern annotations (no @RestController). You MUST infer endpoints from:
                           - HttpServlet methods: doGet, doPost, doPut, doDelete.
                           - Custom routing: Analysis of if/else or switch blocks checking request.getRequestURI().
                           - Action dispatchers: Parameters like request.getParameter("action") that route to different logic.
                        2. STRICT PATHING: NEVER include query strings (e.g., ?action=val) in the "path" field. Extract them into the "parameters" array with in: "QUERY".
                        3. PARAMETER MAPPING:
                           - Map request.getParameter() to "QUERY" for GET and "BODY" for POST (form-urlencoded).
                           - Detect request.getHeader() as "HEADER".
                        4. SOURCE TRACING: You must include the className and the specific methodName where the logic is handled.
                        5. CONFIDENCE SCORING: Provide a score (0.0 to 1.0) based on how explicit the routing is.
                        6. DETERMINISTIC OUTPUT: Return ONLY valid JSON matching the schema. NO markdown, NO text explanations.
                        7. NON-API FILES: If no HTTP logic is found (e.g., a pure Utility or Entity), return {"endpoints": []}.
                        """;

        // Định nghĩa Schema để ép Google trả về đúng chuẩn, không bị lỗi JSON
        private static final String JSON_SCHEMA = """
                        {
                          "type": "object",
                          "properties": {
                            "endpoints": {
                              "type": "array",
                              "items": {
                                "type": "object",
                                "properties": {
                                  "source": {
                                    "type": "object",
                                    "properties": {
                                      "className": {
                                        "type": "string"
                                      },
                                      "methodName": {
                                        "type": "string"
                                      }
                                    },
                                    "required": [
                                      "className",
                                      "methodName"
                                    ],
                                    "propertyOrdering": [
                                      "className",
                                      "methodName"
                                    ]
                                  },
                                  "path": {
                                    "type": "string",
                                    "description": "Clean URL path (e.g., /UserServlet)"
                                  },
                                  "httpMethod": {
                                    "type": "string",
                                    "enum": [
                                      "GET",
                                      "POST",
                                      "PUT",
                                      "DELETE",
                                      "PATCH"
                                    ]
                                  },
                                  "description": {
                                    "type": "string"
                                  },
                                  "parameters": {
                                    "type": "array",
                                    "items": {
                                      "type": "object",
                                      "properties": {
                                        "name": {
                                          "type": "string"
                                        },
                                        "in": {
                                          "type": "string",
                                          "enum": [
                                            "QUERY",
                                            "HEADER",
                                            "PATH",
                                            "BODY"
                                          ]
                                        },
                                        "type": {
                                          "type": "string"
                                        },
                                        "required": {
                                          "type": "boolean"
                                        },
                                        "example": {
                                          "type": "string"
                                        }
                                      },
                                      "required": [
                                        "name",
                                        "in",
                                        "type",
                                        "required"
                                      ],
                                      "propertyOrdering": [
                                        "name",
                                        "in",
                                        "type",
                                        "required",
                                        "example"
                                      ]
                                    }
                                  },
                                  "responses": {
                                    "type": "array",
                                    "items": {
                                      "type": "object",
                                      "properties": {
                                        "statusCode": {
                                          "type": "number"
                                        },
                                        "contentType": {
                                          "type": "string"
                                        }
                                      },
                                      "required": [
                                        "statusCode",
                                        "contentType"
                                      ],
                                      "propertyOrdering": [
                                        "statusCode",
                                        "contentType"
                                      ]
                                    }
                                  },
                                  "confidence": {
                                    "type": "number",
                                    "maximum": 1
                                  }
                                },
                                "required": [
                                  "source",
                                  "path",
                                  "httpMethod",
                                  "parameters",
                                  "responses",
                                  "confidence"
                                ],
                                "propertyOrdering": [
                                  "source",
                                  "path",
                                  "httpMethod",
                                  "description",
                                  "parameters",
                                  "responses",
                                  "confidence"
                                ]
                              }
                            }
                          },
                          "required": [
                            "endpoints"
                          ],
                          "propertyOrdering": [
                            "endpoints"
                          ]
                        }
                        """;

        // Inject WebClient, GeminiProperties và ObjectMapper
        public GeminiApiClientServiceImpl(
                        @Qualifier("geminiWebClient") WebClient webClient,
                        GeminiProperties geminiProperties,
                        ObjectMapper objectMapper) {
                this.webClient = webClient;
                this.geminiProperties = geminiProperties;
                this.objectMapper = objectMapper;
                // KHÔNG configure objectMapper global ở đây.
                // AiJsonParserServiceImpl.mapToDto() dùng objectMapper.copy() để
                // disable FAIL_ON_UNKNOWN_PROPERTIES an toàn, không mutate bean dùng chung.
        }

    // =========================================================================================
    // LOGIC CŨ: Gọi API cơ bản trả về Mono<String> (Dành cho các Task sinh Text
    // thông thường)
    // =========================================================================================
    @Override
    public Mono<String> sendPrompt(String promptText) {
        AiProviderFailureException cooldownFailure = currentCooldownFailure();
        if (cooldownFailure != null) {
                return Mono.error(cooldownFailure);
        }

        // Build DTO Request dựa vào tham số promptText
        Map<String, Object> request = buildTextGenerationPayload(promptText);

        // URI Path động từ model trong properties
        String uriPath = "/" + geminiProperties.getModel() + ":generateContent";

        // Gửi POST request thông qua WebClient
        return webClient.post()
                        .uri(uriPath)
                        .bodyValue(request)
                        .retrieve()
                        .onStatus(status -> status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS),
                                        clientResponse -> {
                                                log.warn("Rate limit bị chạm (429 - TOO MANY REQUESTS) từ Gemini API");
                                                return clientResponse.bodyToMono(String.class)
                                                                .flatMap(errorBody -> Mono.error(buildRateLimitException(errorBody)));
                                        })
                        .onStatus(HttpStatusCode::is5xxServerError, clientResponse -> {
                                log.error("Server error từ Gemini API: {}", clientResponse.statusCode());
                                return clientResponse.bodyToMono(String.class)
                                                .flatMap(errorBody -> Mono.error(
                                                                new RuntimeException("Gemini Server Error ("
                                                                                + clientResponse.statusCode()
                                                                                + "): " + errorBody)));
                        })
                        .onStatus(HttpStatusCode::is4xxClientError, clientResponse -> {
                                log.error("Client error từ Gemini API: {}", clientResponse.statusCode());
                                return clientResponse.bodyToMono(String.class)
                                                .flatMap(errorBody -> Mono.error(
                                                                new RuntimeException("Gemini Client Error ("
                                                                                + clientResponse.statusCode()
                                                                                + "): " + errorBody)));
                        })
                        .bodyToMono(GeminiResponse.class)
                        .map(response -> {
                                String extractedText = response.extractText();
                                if (extractedText == null || extractedText.isBlank()) {
                                        log.warn("Không trích xuất được text từ phản hồi của Gemini: {}",
                                                        response);
                                        throw AiProviderFailureException.emptyResponse(
                                                        "Gemini", geminiProperties.getModel(), null);
                                }
                                return extractedText;
                        })
                        // Thêm cơ chế Retry (Backoff 4 lần, base delay 15 giây)
                        .retryWhen(Retry.backoff(4, Duration.ofSeconds(15))
                                        .filter(this::isRetryableGeminiFailure)
                                        .doBeforeRetry(retrySignal -> log.warn(
                                                        "Đang thử lại lần thứ {}/4 do lỗi: {}",
                                                        retrySignal.totalRetries() + 1,
                                                        retrySignal.failure().getMessage())))
                        .doOnError(e -> log.error("Ngoại lệ xảy ra trong quá trình gọi Gemini API: ", e));
    }

    // =========================================================================================
    // LlmClientService contract: generateText (blocking wrapper cho Router)
    // =========================================================================================

    /**
     * Implements {@link com.aitoolcheck.ai_toolcheck1_backend.service.LlmClientService#generateText(String)}.
     * Wraps {@link #sendPrompt(String)} with a synchronous block() call for use
     * in the {@code AiModelRouterService} fallback chain.
     *
     * <p>A 60-second Reactor timeout is applied before blocking to protect
     * the RabbitMQ consumer thread from hanging indefinitely.
     *
     * @param prompt The full prompt string.
     * @return Raw text response from Gemini.
     */
    @Override
    public String generateText(String prompt) {
        log.info("[GeminiClient][generateText] Gọi Gemini Cloud — độ dài prompt: {} chars", prompt.length());
        return sendPrompt(prompt)
                .timeout(Duration.ofSeconds(geminiProperties.getTimeoutSeconds()),
                        Mono.error(new RuntimeException("[GeminiClient] Timeout " + geminiProperties.getTimeoutSeconds() + "s khi gọi Gemini Cloud.")))
                .doOnError(ex -> log.error("[GeminiClient][generateText] Thất bại: {}", ex.getMessage()))
                .block();
    }

    @Override
    public com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse sendFullPrompt(String prompt) {
        try {
            throwIfCoolingDown();
            log.info("[GeminiClient] sendFullPrompt – độ dài: {} ký tự", prompt.length());

            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("contents", List.of(
                    Map.of("parts", List.of(
                            Map.of("text", prompt)))));

            Map<String, Object> generationConfig = new java.util.HashMap<>();
            generationConfig.put("responseMimeType", "application/json");
            payload.put("generationConfig", generationConfig);

            String uriPath = "/" + geminiProperties.getModel() + ":generateContent?key="
                    + geminiProperties.getApiKey();

            String rawGeminiResponse = webClient.post()
                    .uri(uriPath)
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(status -> status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS),
                            resp -> resp.bodyToMono(String.class)
                                    .flatMap(err -> Mono.error(buildRateLimitException(err))))
                    .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                            .flatMap(err -> Mono.error(new RuntimeException("Gemini API Error: " + err))))
                    .bodyToMono(String.class)
                    .retryWhen(Retry.backoff(4, Duration.ofSeconds(15))
                            .filter(this::isRetryableGeminiFailure)
                            .doBeforeRetry(s -> log.warn("[GeminiClient] Retry lần {}/4 – lý do: {}",
                                    s.totalRetries() + 1, s.failure().getMessage())))
                    .block();

            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            return objectMapper.readValue(rawGeminiResponse, com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse.class);

        } catch (Exception e) {
            log.error("[GeminiClient] sendFullPrompt thất bại: {}", e.getMessage());
            throw new RuntimeException("Lỗi gọi Gemini API (sendFullPrompt): " + e.getMessage(), e);
        }
    }



    // =========================================================================================
    // LOGIC MỚI: AI Skill 0 - Đọc code Legacy trả về DTO chuẩn (Dành cho xử lý chạy
    // ngầm RabbitMQ)
    // =========================================================================================

    /**
     * [Task 3] Gọi Gemini API và trả về raw String chưa parse.
     * Consumer sẽ gọi method này, sau đó đưa String vào AiJsonParserService.
     * Tách bạch hoàn toàn: Gemini Client chỉ lo gọi HTTP, không tự parse.
     */
    @Override
    public GeminiResponse getFullAiResponse(String sourceCode) {
        try {
            throwIfCoolingDown();
            log.info("[GeminiClient] Bắt đầu gọi Gemini API – độ dài source: {} ký tự", sourceCode.length());

            Map<String, Object> payload = buildJsonModePayload(sourceCode);
            String uriPath = "/" + geminiProperties.getModel() + ":generateContent?key="
                    + geminiProperties.getApiKey();

            String rawGeminiResponse = webClient.post()
                    .uri(uriPath)
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(status -> status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS),
                            resp -> resp.bodyToMono(String.class)
                            .flatMap(err -> Mono.error(buildRateLimitException(err))))
                    .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                            .flatMap(err -> Mono.error(new RuntimeException("Gemini API Error: " + err))))
                    .bodyToMono(String.class)
                    .retryWhen(Retry.backoff(4, Duration.ofSeconds(15))
                            .filter(this::isRetryableGeminiFailure)
                            .doBeforeRetry(s -> log.warn("[GeminiClient] Retry lần {}/4 – lý do: {}",
                                    s.totalRetries() + 1, s.failure().getMessage())))
                    .block();

            // Parse json từ Google thành Object chứa cả usageMetadata và content
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            GeminiResponse geminiResponse = objectMapper.readValue(rawGeminiResponse, GeminiResponse.class);
            
            log.info("[GeminiClient] Phản hồi đã parse thành công thành GeminiResponse");
            return geminiResponse;

        } catch (Exception e) {
            log.error("[GeminiClient] getFullAiResponse thất bại: {}", e.getMessage());
            throw new RuntimeException("Lỗi gọi Gemini API (getFullAiResponse): " + e.getMessage(), e);
        }
    }

    /**
     * [Task 3] Gọi Gemini API và trả về raw String chưa parse.
     * Consumer sẽ gọi method này, sau đó đưa String vào AiJsonParserService.
     * Tách bạch hoàn toàn: Gemini Client chỉ lo gọi HTTP, không tự parse.
     */
    @Override
    public String getRawAiResponse(String sourceCode) {
        try {
            GeminiResponse response = getFullAiResponse(sourceCode);
            String extractedText = response.extractText();
            log.info("[GeminiClient] Trích xuất text thô từ GeminiResponse – độ dài: {} ký tự", extractedText != null ? extractedText.length() : 0);
            return extractedText;
        } catch (Exception e) {
            log.error("[GeminiClient] getRawAiResponse thất bại: {}", e.getMessage());
            throw new RuntimeException("Lỗi gọi Gemini API (getRawAiResponse): " + e.getMessage(), e);
        }
    }

    /**
     * @deprecated Dùng getRawAiResponse() + AiJsonParserService.parseToDto() thay thế.
     *             Giữ lại để tránh breaking change.
     */
    @Override
    @Deprecated(since = "Task3", forRemoval = false)
    public AiInferenceResultDto extractLegacyApi(String sourceCode) {
        try {
            log.info("Bắt đầu phân tích file source code (Độ dài: {} ký tự)", sourceCode.length());

            // Delegate sang getRawAiResponse() thay vì duplicate HTTP logic
            String extractedText = getRawAiResponse(sourceCode);

            // Regex dọn rác (markdown fences)
            String cleanJsonString = cleanMarkdownBlocks(extractedText);

            // Parse sang Java DTO
            return objectMapper.readValue(cleanJsonString, AiInferenceResultDto.class);

        } catch (Exception e) {
            log.error("AI Skill 0 Inference Failed: {}", e.getMessage());
            throw new RuntimeException("Lỗi phân tích mã nguồn legacy: " + e.getMessage(), e);
        }
    }

        // --- CÁC HÀM TIỆN ÍCH (HELPER METHODS) ---

        private Map<String, Object> buildJsonModePayload(String sourceCode) {
                Map<String, Object> payload = new HashMap<>();

                // Cài đặt não AI (System Prompt)
                payload.put("system_instruction", Map.of("parts", Map.of("text", LEGACY_EXTRACTOR_PROMPT)));

                // Nội dung cần phân tích
                payload.put("contents", List.of(
                                Map.of("parts", List.of(
                                                Map.of("text", "Analyze the following Java code and output ONLY valid JSON:\n\n"
                                                                + sourceCode)))));

                // Khóa mỏ AI, ép nhả JSON kết hợp truyền Schema
                Map<String, Object> generationConfig = new HashMap<>();
                generationConfig.put("responseMimeType", "application/json");

                try {
                        Map<String, Object> schemaMap = objectMapper.readValue(JSON_SCHEMA, Map.class);
                        generationConfig.put("responseSchema", schemaMap);
                } catch (Exception e) {
                        log.warn("Không parse được JSON_SCHEMA, tiếp tục chỉ dùng MIME Type: {}", e.getMessage());
                }

                payload.put("generationConfig", generationConfig);

                return payload;
        }

        private Map<String, Object> buildTextGenerationPayload(String promptText) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("contents", List.of(
                                Map.of("parts", List.of(
                                                Map.of("text", promptText)))));

                Map<String, Object> generationConfig = new HashMap<>();
                generationConfig.put("responseMimeType", "application/json");
                generationConfig.put("temperature", geminiProperties.getTemperature());
                generationConfig.put("maxOutputTokens", geminiProperties.getMaxOutputTokens());
                payload.put("generationConfig", generationConfig);
                return payload;
        }

        private String extractTextFromGoogleResponse(String rawResponse) throws Exception {
                JsonNode rootNode = objectMapper.readTree(rawResponse);
                JsonNode candidates = rootNode.path("candidates");
                if (candidates.isMissingNode() || candidates.isEmpty()) {
                        throw new RuntimeException("Gemini trả về kết quả rỗng.");
                }
                return candidates.get(0).path("content").path("parts").get(0).path("text").asText();
        }

        private String cleanMarkdownBlocks(String rawText) {
                if (rawText == null) return "";
                // Quét tìm đoạn lõi JSON nằm giữa ```json và ```
                Matcher matcher = Pattern.compile("```(?:json)?\\s*(.*?)\\s*```", Pattern.DOTALL).matcher(rawText);
                if (matcher.find()) {
                        return matcher.group(1);
                }
                return rawText;
        }
        AiProviderFailureException buildRateLimitException(String errorBody) {
                Integer retryAfterSeconds = extractRetryAfterSeconds(errorBody);
                if (retryAfterSeconds != null && retryAfterSeconds > 0) {
                        cooldownUntil.set(Instant.now().plusSeconds(retryAfterSeconds));
                }
                return AiProviderFailureException.rateLimited(
                                "Gemini", geminiProperties.getModel(), retryAfterSeconds, null);
        }

        private Integer extractRetryAfterSeconds(String errorBody) {
                if (errorBody == null || errorBody.isBlank()) {
                        return null;
                }
                try {
                        JsonNode root = objectMapper.readTree(errorBody);
                        JsonNode error = root.path("error");
                        boolean isRateLimited = error.path("code").asInt() == 429
                                        || "RESOURCE_EXHAUSTED".equals(error.path("status").asText());
                        if (!isRateLimited) {
                                return null;
                        }
                        for (JsonNode detail : error.path("details")) {
                                if ("type.googleapis.com/google.rpc.RetryInfo".equals(detail.path("@type").asText())) {
                                        return parseRetryDelaySeconds(detail.path("retryDelay").asText());
                                }
                        }
                } catch (Exception e) {
                        log.warn("[GeminiClient] Could not parse 429 RetryInfo: {}", e.getMessage());
                }
                return null;
        }

        private Integer parseRetryDelaySeconds(String retryDelay) {
                if (retryDelay == null || retryDelay.isBlank()) {
                        return null;
                }
                Matcher matcher = Pattern.compile("^(\\d+)(?:\\.\\d+)?s$").matcher(retryDelay.trim());
                if (!matcher.matches()) {
                        return null;
                }
                return Integer.parseInt(matcher.group(1));
        }

        private AiProviderFailureException currentCooldownFailure() {
                Instant until = cooldownUntil.get();
                Instant now = Instant.now();
                if (until == null || !until.isAfter(now)) {
                        return null;
                }
                long seconds = Math.max(1, Duration.between(now, until).toSeconds());
                return AiProviderFailureException.rateLimited(
                                "Gemini", geminiProperties.getModel(), Math.toIntExact(seconds), null);
        }

        private void throwIfCoolingDown() {
                AiProviderFailureException cooldownFailure = currentCooldownFailure();
                if (cooldownFailure != null) {
                        throw cooldownFailure;
                }
        }

        private boolean isRetryableGeminiFailure(Throwable throwable) {
                Throwable unwrapped = reactor.core.Exceptions.unwrap(throwable);
                if (unwrapped instanceof AiProviderFailureException providerFailure
                                && AiProviderFailureException.LLM_RATE_LIMITED.equals(providerFailure.getErrorCode())) {
                        return false;
                }
                String message = throwable == null || throwable.getMessage() == null
                                ? ""
                                : throwable.getMessage().toLowerCase(java.util.Locale.ROOT);
                if (message.contains("429") || message.contains("rate limit") || message.contains("quota")
                                || message.contains("unauthorized") || message.contains("forbidden")
                                || message.contains("401") || message.contains("403")) {
                        return false;
                }
                return message.contains("500") || message.contains("502") || message.contains("503")
                                || message.contains("504") || message.contains("server error")
                                || message.contains("connection") || message.contains("timeout")
                                || message.isBlank();
        }
}
