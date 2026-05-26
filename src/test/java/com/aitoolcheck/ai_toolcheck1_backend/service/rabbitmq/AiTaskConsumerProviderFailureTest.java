package com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.GeminiProperties;
import com.aitoolcheck.ai_toolcheck1_backend.config.properties.OllamaProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiInferenceResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.rabbitmq.AiTaskMessage;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.LogStatus;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiJobLogRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceFileRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiEndpointService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJsonParserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiModelRouterService;
import com.aitoolcheck.ai_toolcheck1_backend.service.DocumentEnrichmentService;
import com.aitoolcheck.ai_toolcheck1_backend.service.GeminiApiClientService;
import com.aitoolcheck.ai_toolcheck1_backend.service.LegacyInferenceLogService;
import com.aitoolcheck.ai_toolcheck1_backend.service.OllamaApiClientService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestCaseService;
import com.aitoolcheck.ai_toolcheck1_backend.service.ai.AiProviderErrorClassifier;
import com.aitoolcheck.ai_toolcheck1_backend.service.legacy.LegacyRuleBasedEndpointExtractorService;
import com.aitoolcheck.ai_toolcheck1_backend.service.legacy.LegacySourceContextReducerService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiTaskConsumerProviderFailureTest {

    @Mock private GeminiApiClientService geminiApiClientService;
    @Mock private AiJsonParserService aiJsonParserService;
    @Mock private AiTaskPersistenceService persistenceService;
    @Mock private LegacyInferenceLogService legacyInferenceLogService;
    @Mock private AiJobLogRepository aiJobLogRepository;
    @Mock private SourceProjectRepository sourceProjectRepository;
    @Mock private com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService aiJobLogService;
    @Mock private DocumentEnrichmentService documentEnrichmentService;
    @Mock private ApiEndpointService apiEndpointService;
    @Mock private TestCaseService testCaseService;
    @Mock private AiModelRouterService aiModelRouterService;
    @Mock private OllamaApiClientService ollamaApiClientService;
    @Mock private SourceFileRepository sourceFileRepository;
    @Mock private LegacySourceContextReducerService contextReducerService;
    @Mock private LegacyRuleBasedEndpointExtractorService ruleBasedEndpointExtractorService;
    @Mock private Channel channel;

    private AiTaskConsumer consumer;
    private final UUID jobId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID sourceFileId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        OllamaProperties ollamaProperties = new OllamaProperties();
        ollamaProperties.setPrimaryModel("qwen2.5-coder:7b");
        GeminiProperties geminiProperties = new GeminiProperties();
        geminiProperties.setModel("gemini-2.5-flash");

        consumer = new AiTaskConsumer(
                geminiApiClientService,
                aiJsonParserService,
                persistenceService,
                legacyInferenceLogService,
                aiJobLogRepository,
                sourceProjectRepository,
                aiJobLogService,
                documentEnrichmentService,
                apiEndpointService,
                testCaseService,
                aiModelRouterService,
                ollamaProperties,
                ollamaApiClientService,
                geminiProperties,
                sourceFileRepository,
                contextReducerService,
                ruleBasedEndpointExtractorService,
                new AiProviderErrorClassifier());
    }

    @Test
    void providerFailureWithNoRuleFallback_marksFailedAndRecordsFailedAuditLog() throws Exception {
        SourceProject project = new SourceProject();
        project.setId(projectId);

        AiJobLog job = AiJobLog.builder()
                .id(jobId)
                .sourceProject(project)
                .executionStatus(ExecutionStatus.PENDING)
                .jobType(JobType.LEGACY_INFERENCE)
                .build();

        SourceFile sourceFile = new SourceFile();
        sourceFile.setId(sourceFileId);
        sourceFile.setSourceProject(project);
        sourceFile.setFileName("PlainGateway.java");
        sourceFile.setFilePath("/src/main/java/acme/PlainGateway.java");
        sourceFile.setActiveFlag(true);
        sourceFile.setDeletedFlag(false);
        sourceFile.setSourceContent("public class PlainGateway { public void handle() {} }");

        AiInferenceResultDto emptyFallback = new AiInferenceResultDto();
        emptyFallback.setEndpoints(List.of());

        when(aiJobLogRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(sourceFileRepository.findById(sourceFileId)).thenReturn(Optional.of(sourceFile));
        when(ruleBasedEndpointExtractorService.extract(sourceFile)).thenReturn(emptyFallback);
        when(contextReducerService.reduce(any(), any())).thenReturn(sourceFile.getSourceContent());
        when(geminiApiClientService.getFullAiResponse(any()))
                .thenThrow(new RuntimeException("Retries exhausted: 4/4"));
        when(ollamaApiClientService.generateText(any()))
                .thenThrow(new RuntimeException(new TimeoutException("Ollama timeout after 90s")));

        AiTaskMessage message = AiTaskMessage.builder()
                .jobId(jobId.toString())
                .projectId(projectId.toString())
                .sourceFileId(sourceFileId.toString())
                .skillCode("legacy_code_reader")
                .promptText("extract endpoints")
                .build();

        consumer.processAiTask(message, 10L, channel);

        ArgumentCaptor<String> failureCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiJobLogService).markJobAsFailed(eq(jobId), failureCaptor.capture());
        assertTrue(failureCaptor.getValue().contains("AI_PROVIDER_FAILED"));
        assertFalse(failureCaptor.getValue().contains("DTO_VALIDATION_FAILED"));

        verify(legacyInferenceLogService).createLog(
                eq(projectId),
                eq(sourceFileId),
                eq(null),
                failureCaptor.capture(),
                eq(null),
                eq(null),
                eq(LogStatus.FAILED),
                eq("AI_PROVIDER_FAILED"));
        assertTrue(failureCaptor.getValue().contains("AI_PROVIDER_FAILED"));

        verify(persistenceService, never()).persistLegacyInference(any(), any(), any(), any(), any());
        verify(channel).basicAck(10L, false);
    }
}
