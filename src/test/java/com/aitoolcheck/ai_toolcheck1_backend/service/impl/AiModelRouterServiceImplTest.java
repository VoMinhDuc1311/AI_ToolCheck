package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.AiOptimizationProperties;
import com.aitoolcheck.ai_toolcheck1_backend.config.properties.GeminiProperties;
import com.aitoolcheck.ai_toolcheck1_backend.config.properties.OllamaProperties;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiProviderFailureException;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiPayloadOptimizerService;
import com.aitoolcheck.ai_toolcheck1_backend.service.GeminiApiClientService;
import com.aitoolcheck.ai_toolcheck1_backend.service.OllamaApiClientService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.InOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiModelRouterServiceImplTest {

    @Mock private OllamaApiClientService ollamaApiClientService;
    @Mock private GeminiApiClientService geminiApiClientService;
    @Mock private AiJobLogService aiJobLogService;
    @Mock private AiPayloadOptimizerService aiPayloadOptimizerService;

    private OllamaProperties ollamaProperties;
    private GeminiProperties geminiProperties;
    private AiOptimizationProperties optimizationProperties;
    private AiModelRouterServiceImpl router;

    @BeforeEach
    void setUp() {
        System.setProperty("ai.router.geminiThrottleMs", "0");

        ollamaProperties = new OllamaProperties();
        ollamaProperties.setPrimaryModel("qwen2.5-coder:7b");
        ollamaProperties.setFallbackModel("qwen2.5-coder:7b");
        ollamaProperties.setReadTimeoutSeconds(90);

        geminiProperties = new GeminiProperties();
        geminiProperties.setModel("gemini-2.5-flash");

        optimizationProperties = new AiOptimizationProperties();
        optimizationProperties.setEnabled(false);
        optimizationProperties.getRouter().setFailFastLocalProvider(false);

        router = new AiModelRouterServiceImpl(
                ollamaApiClientService,
                geminiApiClientService,
                ollamaProperties,
                aiJobLogService,
                optimizationProperties,
                aiPayloadOptimizerService,
                geminiProperties);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("ai.router.geminiThrottleMs");
    }

    @Test
    void executeWithFallback_skipsDuplicateOllamaProviderModelAndProceedsToGemini() {
        when(ollamaApiClientService.generateTextWithModel(anyString(), eq("qwen2.5-coder:7b"), anyInt()))
                .thenThrow(AiProviderFailureException.timeout("Ollama", "qwen2.5-coder:7b", 90, 42, null));
        when(geminiApiClientService.generateText(anyString())).thenReturn("{\"summary\":\"ok\"}");

        String result = router.executeWithFallback("prompt");

        assertEquals("{\"summary\":\"ok\"}", result);
        verify(ollamaApiClientService).generateTextWithModel(anyString(), eq("qwen2.5-coder:7b"), eq(90));
        verify(geminiApiClientService).generateText(anyString());
    }

    @Test
    void executeWithFallback_whenAllProvidersFailThrowsAllProvidersFailedNotJsonParseException() {
        when(ollamaApiClientService.generateTextWithModel(anyString(), eq("qwen2.5-coder:7b"), anyInt()))
                .thenThrow(AiProviderFailureException.timeout("Ollama", "qwen2.5-coder:7b", 90, 42, null));
        when(geminiApiClientService.generateText(anyString()))
                .thenThrow(AiProviderFailureException.rateLimited("Gemini", "gemini-2.5-flash", 34, null));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> router.executeWithFallback("prompt"));

        AiProviderFailureException providerFailure = assertInstanceOf(AiProviderFailureException.class, exception);
        assertEquals(AiProviderFailureException.LLM_ALL_PROVIDERS_FAILED, providerFailure.getErrorCode());
        assertFalse(exception instanceof AiJsonParseException);
        assertFalse(providerFailure.getMessage().contains("INVALID_JSON_SYNTAX"));
    }

    @Test
    void executeWithFallback_whenOllamaHealthFailsGeminiRateLimitedDoesNotRetryOllamaAfterGemini() {
        optimizationProperties.setEnabled(true);
        optimizationProperties.getRouter().setFailFastLocalProvider(true);
        when(ollamaApiClientService.isHealthy()).thenReturn(false);
        when(aiPayloadOptimizerService.truncateIfNeeded(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(geminiApiClientService.generateText(anyString()))
                .thenThrow(AiProviderFailureException.rateLimited("Gemini", "gemini-2.5-flash", 25, null));

        AiProviderFailureException exception = assertThrows(
                AiProviderFailureException.class,
                () -> router.executeWithFallback("prompt"));

        assertEquals(AiProviderFailureException.LLM_ALL_PROVIDERS_FAILED, exception.getErrorCode());
        verify(ollamaApiClientService, never()).generateTextWithModel(anyString(), eq("qwen2.5-coder:7b"), anyInt());
        verify(geminiApiClientService).generateText(anyString());
    }

    @Test
    void executeWithFallback_whenGeminiCooldownFailureOccursAfterOllamaFailuresFinalErrorIsAllProvidersFailed() {
        ollamaProperties.setFallbackModel("llama3.1:8b");
        when(ollamaApiClientService.generateTextWithModel(anyString(), eq("qwen2.5-coder:7b"), anyInt()))
                .thenThrow(AiProviderFailureException.timeout("Ollama", "qwen2.5-coder:7b", 90, 42, null));
        when(ollamaApiClientService.generateTextWithModel(anyString(), eq("llama3.1:8b"), anyInt()))
                .thenThrow(AiProviderFailureException.timeout("Ollama", "llama3.1:8b", 90, 42, null));
        when(geminiApiClientService.generateText(anyString()))
                .thenThrow(AiProviderFailureException.rateLimited("Gemini", "gemini-2.5-flash", 25, null));

        AiProviderFailureException exception = assertThrows(
                AiProviderFailureException.class,
                () -> router.executeWithFallback("prompt"));

        assertEquals(AiProviderFailureException.LLM_ALL_PROVIDERS_FAILED, exception.getErrorCode());
        verify(ollamaApiClientService).generateTextWithModel(anyString(), eq("qwen2.5-coder:7b"), eq(90));
        verify(ollamaApiClientService).generateTextWithModel(anyString(), eq("llama3.1:8b"), eq(90));
        verify(geminiApiClientService).generateText(anyString());
    }

    @Test
    void enrichApiDoc_providerOrder_isGeminiThenOllama() {
        ollamaProperties.setEnrichApiDocTimeoutSeconds(180);
        when(geminiApiClientService.generateText(eq("gemini prompt")))
                .thenThrow(AiProviderFailureException.rateLimited("Gemini", "gemini-2.5-flash", 25, null));
        when(ollamaApiClientService.generateTextWithModel(eq("ollama prompt"), eq("qwen2.5-coder:7b"), eq(180)))
                .thenReturn("{\"summary\":\"ok\"}");

        String result = router.executeWithFallbackForSkill(
                "enrich_api_doc",
                () -> "gemini prompt",
                () -> "ollama prompt",
                raw -> {});

        assertEquals("{\"summary\":\"ok\"}", result);
        InOrder inOrder = inOrder(geminiApiClientService, ollamaApiClientService);
        inOrder.verify(geminiApiClientService).generateText(eq("gemini prompt"));
        inOrder.verify(ollamaApiClientService)
                .generateTextWithModel(eq("ollama prompt"), eq("qwen2.5-coder:7b"), eq(180));
    }

    @Test
    void gemini429_thenOllamaSuccess_returnsOllamaResult() {
        when(geminiApiClientService.generateText(anyString()))
                .thenThrow(AiProviderFailureException.rateLimited("Gemini", "gemini-2.5-flash", 34, null));
        when(ollamaApiClientService.generateTextWithModel(anyString(), eq("qwen2.5-coder:7b"), eq(180)))
                .thenReturn("{\"summary\":\"ollama ok\"}");

        String result = router.executeWithFallbackForSkill(
                "enrich_api_doc",
                () -> "gemini prompt",
                () -> "ollama prompt",
                raw -> {});

        assertEquals("{\"summary\":\"ollama ok\"}", result);
    }

    @Test
    void geminiCooldown_thenOllamaSuccess_returnsOllamaResult() {
        when(geminiApiClientService.generateText(anyString()))
                .thenThrow(AiProviderFailureException.rateLimited("Gemini", "gemini-2.5-flash", 12, null));
        when(ollamaApiClientService.generateTextWithModel(anyString(), eq("qwen2.5-coder:7b"), eq(180)))
                .thenReturn("{\"summary\":\"ollama ok\"}");

        String result = router.executeWithFallbackForSkill(
                "enrich_api_doc",
                () -> "gemini prompt",
                () -> "ollama prompt",
                raw -> {});

        assertEquals("{\"summary\":\"ollama ok\"}", result);
    }

    @Test
    void gemini429_thenOllamaTimeout_returnsAllProvidersFailed() {
        when(geminiApiClientService.generateText(anyString()))
                .thenThrow(AiProviderFailureException.rateLimited("Gemini", "gemini-2.5-flash", 34, null));
        when(ollamaApiClientService.generateTextWithModel(anyString(), eq("qwen2.5-coder:7b"), eq(180)))
                .thenThrow(AiProviderFailureException.timeout("Ollama", "qwen2.5-coder:7b", 180, 1200, null));

        AiProviderFailureException exception = assertThrows(
                AiProviderFailureException.class,
                () -> router.executeWithFallbackForSkill(
                        "enrich_api_doc",
                        () -> "gemini prompt",
                        () -> "ollama prompt",
                        raw -> {}));

        assertEquals(AiProviderFailureException.LLM_ALL_PROVIDERS_FAILED, exception.getErrorCode());
        assertFalse(exception.getMessage().contains("INVALID_JSON_SYNTAX"));
        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("retry after 34s")
                .contains("timed out after 180s");
    }
}
