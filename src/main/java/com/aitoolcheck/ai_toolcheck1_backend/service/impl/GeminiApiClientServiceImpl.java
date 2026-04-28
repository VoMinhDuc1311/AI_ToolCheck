package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.GeminiProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiInferenceResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.req.GeminiRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class GeminiApiClientServiceImpl implements GeminiApiClientService {

        private final WebClient webClient;
        private final GeminiProperties geminiProperties;
        private final ObjectMapper objectMapper;

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

                // Cấu hình ObjectMapper chịu lỗi: bỏ qua field lạ nếu AI ảo giác sinh thêm
                this.objectMapper = objectMapper;
                this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        }

        // =========================================================================================
        // LOGIC CŨ: Gọi API cơ bản trả về Mono<String> (Dành cho các Task sinh Text
        // thông thường)
        // =========================================================================================
        @Override
        public Mono<String> sendPrompt(String promptText) {
                // Build DTO Request dựa vào tham số promptText
                GeminiRequest request = GeminiRequest.builder()
                                .contents(List.of(
                                                GeminiRequest.Content.builder()
                                                                .parts(List.of(
                                                                                GeminiRequest.Part.builder()
                                                                                                .text(promptText)
                                                                                                .build()))
                                                                .build()))
                                .build();

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
                                                                        .flatMap(errorBody -> Mono.error(
                                                                                        new RuntimeException(
                                                                                                        "Rate Limit Exceeded (429): "
                                                                                                                        + errorBody)));
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
                                        if (extractedText == null) {
                                                log.warn("Không trích xuất được text từ phản hồi của Gemini: {}",
                                                                response);
                                                throw new RuntimeException(
                                                                "Failed to extract text from Gemini API response");
                                        }
                                        return extractedText;
                                })
                                // Thêm cơ chế Retry (Backoff 3 lần, delay 2 giây)
                                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                                                .doBeforeRetry(retrySignal -> log.warn(
                                                                "Đang thử lại lần thứ {}/3 do lỗi: {}",
                                                                retrySignal.totalRetries() + 1,
                                                                retrySignal.failure().getMessage())))
                                .doOnError(e -> log.error("Ngoại lệ xảy ra trong quá trình gọi Gemini API: ", e));
        }

        // =========================================================================================
        // LOGIC MỚI: AI Skill 0 - Đọc code Legacy trả về DTO chuẩn (Dành cho xử lý chạy
        // ngầm RabbitMQ)
        // =========================================================================================
        @Override
        public AiInferenceResultDto extractLegacyApi(String sourceCode) {
                try {
                        log.info("Bắt đầu phân tích file source code (Độ dài: {} ký tự)", sourceCode.length());

                        // 1. Build Payload với cấu hình JSON Mode và SCHEMAS
                        Map<String, Object> payload = buildJsonModePayload(sourceCode);
                        String uriPath = "/" + geminiProperties.getModel() + ":generateContent?key="
                                        + geminiProperties.getApiKey();

                        // 2. Gọi API & Xử lý lỗi/Retry
                        String rawResponse = webClient.post()
                                        .uri(uriPath)
                                        .bodyValue(payload)
                                        .retrieve()
                                        .onStatus(status -> status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS),
                                                        resp -> resp.bodyToMono(String.class)
                                                                        .flatMap(err -> Mono.error(new RuntimeException(
                                                                                        "Rate Limit 429"))))
                                        .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                                                        .flatMap(err -> Mono.error(
                                                                        new RuntimeException("API Error: " + err))))
                                        .bodyToMono(String.class)
                                        .retryWhen(Retry.backoff(3, Duration.ofSeconds(2)))
                                        .block(); // Chặn luồng vì method này sẽ được gọi từ Background Thread của
                                                  // RabbitMQ

                        // 3. Bóc lõi Text từ cục JSON tổng của Google
                        String extractedText = extractTextFromGoogleResponse(rawResponse);

                        // 4. Regex Dọn rác (Cắt bỏ ```json ... ```)
                        String cleanJsonString = cleanMarkdownBlocks(extractedText);

                        // 5. Parse sang Java DTO
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
                        JsonNode schemaNode = objectMapper.readTree(JSON_SCHEMA);
                        generationConfig.put("responseSchema", schemaNode);
                } catch (Exception e) {
                        log.warn("Không parse được JSON_SCHEMA, tiếp tục chỉ dùng MIME Type: {}", e.getMessage());
                }

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
}