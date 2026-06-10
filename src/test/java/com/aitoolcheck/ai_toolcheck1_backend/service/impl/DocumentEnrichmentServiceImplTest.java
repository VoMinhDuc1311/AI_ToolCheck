package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.AiOptimizationProperties;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJsonParserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiModelRouterService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiPayloadOptimizerService;
import com.aitoolcheck.ai_toolcheck1_backend.service.GeminiApiClientService;
import com.aitoolcheck.ai_toolcheck1_backend.service.VectorSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DocumentEnrichmentServiceImplTest {

    private final DocumentEnrichmentServiceImpl service = new DocumentEnrichmentServiceImpl(
            mock(AiModelRouterService.class),
            mock(VectorSearchService.class),
            mock(AiJsonParserService.class),
            mock(GeminiApiClientService.class),
            mock(AiPayloadOptimizerService.class),
            new AiOptimizationProperties(),
            new ObjectMapper());

    @Test
    void ollamaFallbackPrompt_isCompressedForEnrichApiDoc() {
        String longRagContext = "RAG_CONTEXT_SHOULD_NOT_BE_INCLUDED ".repeat(120);
        String openApiFragment = """
                {
                  "/api/users/{id}": {
                    "get": {
                      "controllerName": "UserController",
                      "operationId": "getUserById",
                      "parameters": [
                        {"name": "id", "in": "path"},
                        {"name": "includeRoles", "in": "query"}
                      ],
                      "requestBody": {
                        "content": {
                          "application/json": {
                            "schema": {
                              "type": "object",
                              "properties": {
                                "filter": {"type": "string"}
                              }
                            }
                          }
                        }
                      },
                      "responses": {
                        "200": {
                          "content": {
                            "application/json": {
                              "schema": {
                                "type": "object",
                                "properties": {
                                  "id": {"type": "string"},
                                  "email": {"type": "string"}
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  },
                  "irrelevantLongContext": "%s"
                }
                """.formatted(longRagContext);

        String prompt = service.buildOllamaEnrichPrompt(openApiFragment);

        assertThat(prompt.length()).isLessThanOrEqualTo(3000);
        assertThat(prompt)
                .contains("Return ONLY one valid JSON object")
                .contains("HTTP method: GET")
                .contains("Path: /api/users/{id}")
                .contains("Controller/Class: UserController")
                .contains("Operation/Method: getUserById")
                .contains("Path params: id")
                .contains("Query params: includeRoles")
                .contains("Output schema exactly")
                .doesNotContain("RAG_CONTEXT_SHOULD_NOT_BE_INCLUDED RAG_CONTEXT_SHOULD_NOT_BE_INCLUDED");
    }
}
