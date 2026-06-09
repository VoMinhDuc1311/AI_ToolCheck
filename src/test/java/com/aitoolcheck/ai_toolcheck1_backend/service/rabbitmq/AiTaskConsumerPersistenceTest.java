package com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.GeminiProperties;
import com.aitoolcheck.ai_toolcheck1_backend.config.properties.OllamaProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiInferenceResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.rabbitmq.AiTaskMessage;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiPersistenceException;
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
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiMetadataCleanupService;
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
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiTaskConsumerPersistenceTest {

    @Mock private GeminiApiClientService geminiApiClientService;
    @Mock private AiJsonParserService aiJsonParserService;
    @Mock private AiTaskPersistenceService persistenceService;
    @Mock private LegacyInferenceLogService legacyInferenceLogService;
    @Mock private ApiMetadataCleanupService apiMetadataCleanupService;
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

    @Mock private GeminiResponse geminiResponse;

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
                apiMetadataCleanupService,
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
    void persistSuccess_callsCleanupAndJobTransitionsSuccess() throws Exception {
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

        AiInferenceResultDto resultDto = new AiInferenceResultDto();
        resultDto.setEndpoints(List.of(new AiInferenceResultDto.EndpointDto()));

        when(aiJobLogRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(sourceFileRepository.findById(sourceFileId)).thenReturn(Optional.of(sourceFile));
        when(geminiApiClientService.getFullAiResponse(anyString())).thenReturn(geminiResponse);
        when(geminiResponse.extractText()).thenReturn("{JSON_CONTENT}");
        when(aiJsonParserService.extractAndSanitizeJson(anyString())).thenReturn("{JSON_CONTENT}");
        when(aiJsonParserService.parseToDto(anyString())).thenReturn(resultDto);
        when(sourceProjectRepository.getReferenceById(projectId)).thenReturn(project);

        AiTaskMessage message = AiTaskMessage.builder()
                .jobId(jobId.toString())
                .projectId(projectId.toString())
                .sourceFileId(sourceFileId.toString())
                .skillCode("legacy_code_reader")
                .promptText("extract endpoints")
                .build();

        consumer.processAiTask(message, 10L, channel);

        // Verify persistence and cleanup were called
        verify(persistenceService).persistLegacyInference(eq(project), eq(sourceFileId), eq("{JSON_CONTENT}"), eq("{JSON_CONTENT}"), eq(resultDto));
        verify(apiMetadataCleanupService).cleanupProjectApiMetadata(eq(projectId));

        // Verify status updates and ACK
        verify(aiJobLogService).markJobAsRunning(eq(jobId));
        verify(aiJobLogService).markJobAsSuccess(eq(jobId), any(), any(), any(), any());
        verify(channel).basicAck(10L, false);
    }

    @Test
    void persistFailureWithDatabaseIntegrityViolation_jobTransitionsFailedAndExposesRootCause() throws Exception {
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

        AiInferenceResultDto resultDto = new AiInferenceResultDto();
        resultDto.setEndpoints(List.of(new AiInferenceResultDto.EndpointDto()));

        when(aiJobLogRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(sourceFileRepository.findById(sourceFileId)).thenReturn(Optional.of(sourceFile));
        when(geminiApiClientService.getFullAiResponse(anyString())).thenReturn(geminiResponse);
        when(geminiResponse.extractText()).thenReturn("{JSON_CONTENT}");
        when(aiJsonParserService.extractAndSanitizeJson(anyString())).thenReturn("{JSON_CONTENT}");
        when(aiJsonParserService.parseToDto(anyString())).thenReturn(resultDto);
        when(sourceProjectRepository.getReferenceById(projectId)).thenReturn(project);

        // Mock DB Exception in persistence
        doThrow(new DataIntegrityViolationException("Duplicate key value violates unique constraint stable_key"))
                .when(persistenceService).persistLegacyInference(any(), any(), any(), any(), any());

        AiTaskMessage message = AiTaskMessage.builder()
                .jobId(jobId.toString())
                .projectId(projectId.toString())
                .sourceFileId(sourceFileId.toString())
                .skillCode("legacy_code_reader")
                .promptText("extract endpoints")
                .build();

        consumer.processAiTask(message, 10L, channel);

        // Verify status updates and failure details
        verify(aiJobLogService).markJobAsRunning(eq(jobId));
        ArgumentCaptor<String> failureCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiJobLogService).markJobAsFailed(eq(jobId), failureCaptor.capture());

        // Verify that error message exposes the DataIntegrityViolationException root cause
        String errorMsg = failureCaptor.getValue();
        assertTrue(errorMsg.contains("DataIntegrityViolationException") || errorMsg.contains("Duplicate key value violates unique constraint stable_key"));
        verify(channel).basicAck(10L, false);
    }
}
