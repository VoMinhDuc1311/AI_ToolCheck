package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apimetadata.res.ApiMetadataParseResultResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.openapi.res.OpenApiGenerateResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceanalysisresult.res.SourceAnalysisResultDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceDocumentationPipelineResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.SourceStyle;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiSkillRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceFileRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiMetadataParserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.OpenApiGeneratorService;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceAnalysisResultService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.legacy.LegacyEntrypointClassifierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SourceDocumentationOrchestratorServiceImpl.
 *
 * <p>Key invariants verified:
 * <ul>
 *   <li>With one parsed endpoint, pipeline returns openApiGenerated=true — no UnexpectedRollbackException.</li>
 *   <li>When OpenAPI generation fails with BadRequestException (no endpoints), orchestrator
 *       returns openApiGenerated=false cleanly — NOT a 500 rollback-only error.</li>
 *   <li>When parserExecuted=false and aiRecommended=false, OpenAPI generation is not attempted.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SourceDocumentationOrchestratorServiceImplTest {

    @Mock private SourceAnalysisResultService sourceAnalysisResultService;
    @Mock private ApiMetadataParserService apiMetadataParserService;
    @Mock private AiJobLogService aiJobLogService;
    @Mock private OpenApiGeneratorService openApiGeneratorService;
    @Mock private ProjectAccessService projectAccessService;
    @Mock private SourceFileRepository sourceFileRepository;
    @Mock private AiSkillRepository aiSkillRepository;
    @Mock private LegacyEntrypointClassifierService classifierService;

    private SourceDocumentationOrchestratorServiceImpl orchestrator;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        orchestrator = new SourceDocumentationOrchestratorServiceImpl(
                sourceAnalysisResultService,
                apiMetadataParserService,
                aiJobLogService,
                openApiGeneratorService,
                projectAccessService,
                sourceFileRepository,
                aiSkillRepository,
                classifierService
        );
        projectId = UUID.randomUUID();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Happy path — parserRecommended=true, 1 endpoint, OpenAPI generated
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void withOneEndpoint_generateDocsFromSource_returnsOpenApiGenerated() {
        SourceProject project = new SourceProject();
        project.setId(projectId);

        // Simulate analysis: parserRecommended, not AI
        SourceAnalysisResultDetailResponse analysis = SourceAnalysisResultDetailResponse.builder()
                .projectId(projectId)
                .sourceStyle(SourceStyle.MODERN)
                .parserRecommended(true)
                .aiRecommended(false)
                .build();

        // Simulate parse: 1 endpoint found
        ApiMetadataParseResultResponse parseResult = ApiMetadataParseResultResponse.builder()
                .projectId(projectId)
                .totalEndpoints(1)
                .totalSchemas(1)
                .build();

        // Simulate OpenAPI generation success
        OpenApiGenerateResponse openApiResp = OpenApiGenerateResponse.builder()
                .projectId(projectId)
                .apiDocumentVersionId(UUID.randomUUID())
                .versionNo(1)
                .totalEndpoints(1)
                .build();

        when(projectAccessService.requireCanGenerateDocs(projectId)).thenReturn(project);
        when(sourceAnalysisResultService.analyzeProject(projectId)).thenReturn(analysis);
        when(apiMetadataParserService.parseProject(projectId)).thenReturn(parseResult);
        when(openApiGeneratorService.generateAndSaveOpenApi(projectId)).thenReturn(openApiResp);

        // Must not throw — specifically must not throw UnexpectedRollbackException
        SourceDocumentationPipelineResponse response = assertDoesNotThrow(
                () -> orchestrator.generateDocsFromSource(projectId),
                "generateDocsFromSource must not throw when pipeline succeeds"
        );

        assertNotNull(response);
        assertThat(response.getOpenApiGenerated()).isTrue();
        assertThat(response.getParserExecuted()).isTrue();
        assertThat(response.getAiJobsTriggered()).isEqualTo(0);
        assertThat(response.getApiDocumentVersionId()).isEqualTo(openApiResp.getApiDocumentVersionId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // No endpoints — OpenAPI generation fails with BadRequestException
    // Pipeline must return openApiGenerated=false, NOT throw rollback-only 500
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void whenOpenApiGenerationFails_returnsCleanPipelineResponse_notRollbackOnly500() {
        SourceProject project = new SourceProject();
        project.setId(projectId);

        SourceAnalysisResultDetailResponse analysis = SourceAnalysisResultDetailResponse.builder()
                .projectId(projectId)
                .sourceStyle(SourceStyle.MODERN)
                .parserRecommended(true)
                .aiRecommended(false)
                .build();

        ApiMetadataParseResultResponse parseResult = ApiMetadataParseResultResponse.builder()
                .projectId(projectId)
                .totalEndpoints(0)
                .totalSchemas(0)
                .build();

        when(projectAccessService.requireCanGenerateDocs(projectId)).thenReturn(project);
        when(sourceAnalysisResultService.analyzeProject(projectId)).thenReturn(analysis);
        when(apiMetadataParserService.parseProject(projectId)).thenReturn(parseResult);
        // Cleanup marks everything stale → OpenAPI generator throws BadRequestException
        when(openApiGeneratorService.generateAndSaveOpenApi(projectId))
                .thenThrow(new BadRequestException("No API endpoints found for project id: " + projectId));

        // Must return cleanly — must NOT propagate the exception as a 500 rollback-only
        SourceDocumentationPipelineResponse response = assertDoesNotThrow(
                () -> orchestrator.generateDocsFromSource(projectId),
                "generateDocsFromSource must not throw when OpenAPI generation fails with expected business error"
        );

        assertNotNull(response);
        assertFalse(response.getOpenApiGenerated(), "openApiGenerated must be false when no endpoints exist");
        assertThat(response.getParserExecuted()).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // parserExecuted=false + aiRecommended=false → OpenAPI generation skipped
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void whenParserNotExecuted_openApiGenerationIsSkipped() {
        SourceProject project = new SourceProject();
        project.setId(projectId);

        SourceAnalysisResultDetailResponse analysis = SourceAnalysisResultDetailResponse.builder()
                .projectId(projectId)
                .sourceStyle(SourceStyle.MODERN)
                .parserRecommended(false)
                .aiRecommended(false)
                .build();

        when(projectAccessService.requireCanGenerateDocs(projectId)).thenReturn(project);
        when(sourceAnalysisResultService.analyzeProject(projectId)).thenReturn(analysis);

        SourceDocumentationPipelineResponse response = assertDoesNotThrow(
                () -> orchestrator.generateDocsFromSource(projectId)
        );

        assertNotNull(response);
        assertFalse(response.getOpenApiGenerated());
        assertFalse(response.getParserExecuted());
        // OpenAPI generation must not have been called
        verify(openApiGeneratorService, never()).generateAndSaveOpenApi(projectId);
    }
}
