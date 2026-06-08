package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apimetadata.res.ApiMetadataParseResultResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceanalysisresult.res.SourceAnalysisResultDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.SourceStyle;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceAnalysisResult;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceUploadVersion;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceAnalysisResultRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceFileRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceUploadVersionRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiMetadataParserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.analysis.AnalysisSignals;
import com.aitoolcheck.ai_toolcheck1_backend.service.analysis.JavaSourceAnalyzer;
import com.aitoolcheck.ai_toolcheck1_backend.service.analysis.SourceAnalysisDecisionService;
import com.aitoolcheck.ai_toolcheck1_backend.service.analysis.SourceAnalysisScoringService;
import com.aitoolcheck.ai_toolcheck1_backend.service.analysis.SourceAnalysisSummaryBuilder;
import com.aitoolcheck.ai_toolcheck1_backend.service.notification.ProjectNotificationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SourceAnalysisResultServiceImplTest {

    @Mock private SourceAnalysisResultRepository sourceAnalysisResultRepository;
    @Mock private SourceFileRepository sourceFileRepository;
    @Mock private SourceProjectRepository sourceProjectRepository;
    @Mock private SourceUploadVersionRepository sourceUploadVersionRepository;
    @Mock private JavaSourceAnalyzer javaSourceAnalyzer;
    @Mock private SourceAnalysisScoringService scoringService;
    @Mock private SourceAnalysisDecisionService decisionService;
    @Mock private SourceAnalysisSummaryBuilder summaryBuilder;
    @Mock private ProjectAccessService projectAccessService;
    @Mock private ProjectNotificationEventPublisher notificationEventPublisher;
    @Mock private ApiMetadataParserService apiMetadataParserService;

    private SourceAnalysisResultServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SourceAnalysisResultServiceImpl(
                sourceAnalysisResultRepository,
                sourceFileRepository,
                sourceProjectRepository,
                sourceUploadVersionRepository,
                javaSourceAnalyzer,
                scoringService,
                decisionService,
                summaryBuilder,
                projectAccessService,
                notificationEventPublisher,
                apiMetadataParserService
        );
    }

    @Test
    void analyzeProject_callsApiMetadataParser_whenParserRecommended() {
        UUID projectId = UUID.randomUUID();
        SourceProject project = new SourceProject();
        project.setId(projectId);
        project.setProjectName("Test Project");

        SourceFile file = SourceFile.builder()
                .fileName("UserController.java")
                .sourceContent("package com.test; public class UserController {}")
                .build();
        List<SourceFile> files = List.of(file);

        AnalysisSignals signals = mock(AnalysisSignals.class);
        when(signals.parsedSuccessFiles()).thenReturn(1);

        SourceAnalysisResult analysisResult = SourceAnalysisResult.builder()
                .id(UUID.randomUUID())
                .sourceProject(project)
                .build();

        // Stubbing dependencies
        when(projectAccessService.requireCanTriggerAiJob(projectId)).thenReturn(project);
        when(sourceFileRepository.findBySourceProjectIdAndActiveFlagTrue(projectId)).thenReturn(files);
        when(javaSourceAnalyzer.analyze(eq(projectId), anyList())).thenReturn(signals);
        when(scoringService.calculateRate(anyInt(), anyInt())).thenReturn(1.0);
        when(scoringService.calculateAnnotationScore(any())).thenReturn(100);
        when(scoringService.calculateStructureScore(any(), anyDouble())).thenReturn(100);
        when(decisionService.determineSourceStyle(any(), anyDouble(), anyInt(), anyInt())).thenReturn(SourceStyle.MODERN);
        when(decisionService.determineParserRecommended(anyDouble(), anyInt(), anyInt())).thenReturn(true);
        when(decisionService.determineAiRecommended(any(), anyDouble(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(false);
        when(summaryBuilder.buildSummary(any(), anyBoolean(), anyBoolean())).thenReturn("Summary");
        when(sourceAnalysisResultRepository.findLatestCandidatesByProjectId(projectId)).thenReturn(List.of());
        when(sourceUploadVersionRepository.findTopBySourceProjectIdOrderByVersionNoDesc(projectId)).thenReturn(Optional.empty());
        when(sourceAnalysisResultRepository.save(any(SourceAnalysisResult.class))).thenReturn(analysisResult);

        // Run service method
        SourceAnalysisResultDetailResponse response = service.analyzeProject(projectId);

        // Verification
        assertThat(response).isNotNull();
        verify(apiMetadataParserService).parseProject(projectId, false);
    }

    @Test
    void analyzeProject_doesNotCallApiMetadataParser_whenParserNotRecommended() {
        UUID projectId = UUID.randomUUID();
        SourceProject project = new SourceProject();
        project.setId(projectId);
        project.setProjectName("Test Project");

        SourceFile file = SourceFile.builder()
                .fileName("UserController.java")
                .sourceContent("package com.test; public class UserController {}")
                .build();
        List<SourceFile> files = List.of(file);

        AnalysisSignals signals = mock(AnalysisSignals.class);
        when(signals.parsedSuccessFiles()).thenReturn(1);

        SourceAnalysisResult analysisResult = SourceAnalysisResult.builder()
                .id(UUID.randomUUID())
                .sourceProject(project)
                .build();

        // Stubbing dependencies
        when(projectAccessService.requireCanTriggerAiJob(projectId)).thenReturn(project);
        when(sourceFileRepository.findBySourceProjectIdAndActiveFlagTrue(projectId)).thenReturn(files);
        when(javaSourceAnalyzer.analyze(eq(projectId), anyList())).thenReturn(signals);
        when(scoringService.calculateRate(anyInt(), anyInt())).thenReturn(0.5);
        when(scoringService.calculateAnnotationScore(any())).thenReturn(20);
        when(scoringService.calculateStructureScore(any(), anyDouble())).thenReturn(20);
        when(decisionService.determineSourceStyle(any(), anyDouble(), anyInt(), anyInt())).thenReturn(SourceStyle.LEGACY);
        when(decisionService.determineParserRecommended(anyDouble(), anyInt(), anyInt())).thenReturn(false);
        when(decisionService.determineAiRecommended(any(), anyDouble(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(true);
        when(summaryBuilder.buildSummary(any(), anyBoolean(), anyBoolean())).thenReturn("Summary");
        when(sourceAnalysisResultRepository.findLatestCandidatesByProjectId(projectId)).thenReturn(List.of());
        when(sourceUploadVersionRepository.findTopBySourceProjectIdOrderByVersionNoDesc(projectId)).thenReturn(Optional.empty());
        when(sourceAnalysisResultRepository.save(any(SourceAnalysisResult.class))).thenReturn(analysisResult);

        // Run service method
        SourceAnalysisResultDetailResponse response = service.analyzeProject(projectId);

        // Verification
        assertThat(response).isNotNull();
        verify(apiMetadataParserService, never()).parseProject(any(), anyBoolean());
    }

    @Test
    void analyzeProject_swallowsParserExceptions_andSucceeds() {
        UUID projectId = UUID.randomUUID();
        SourceProject project = new SourceProject();
        project.setId(projectId);
        project.setProjectName("Test Project");

        SourceFile file = SourceFile.builder()
                .fileName("UserController.java")
                .sourceContent("package com.test; public class UserController {}")
                .build();
        List<SourceFile> files = List.of(file);

        AnalysisSignals signals = mock(AnalysisSignals.class);
        when(signals.parsedSuccessFiles()).thenReturn(1);

        SourceAnalysisResult analysisResult = SourceAnalysisResult.builder()
                .id(UUID.randomUUID())
                .sourceProject(project)
                .build();

        // Stubbing dependencies
        when(projectAccessService.requireCanTriggerAiJob(projectId)).thenReturn(project);
        when(sourceFileRepository.findBySourceProjectIdAndActiveFlagTrue(projectId)).thenReturn(files);
        when(javaSourceAnalyzer.analyze(eq(projectId), anyList())).thenReturn(signals);
        when(scoringService.calculateRate(anyInt(), anyInt())).thenReturn(1.0);
        when(scoringService.calculateAnnotationScore(any())).thenReturn(100);
        when(scoringService.calculateStructureScore(any(), anyDouble())).thenReturn(100);
        when(decisionService.determineSourceStyle(any(), anyDouble(), anyInt(), anyInt())).thenReturn(SourceStyle.MODERN);
        when(decisionService.determineParserRecommended(anyDouble(), anyInt(), anyInt())).thenReturn(true);
        when(decisionService.determineAiRecommended(any(), anyDouble(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(false);
        when(summaryBuilder.buildSummary(any(), anyBoolean(), anyBoolean())).thenReturn("Summary");
        when(sourceAnalysisResultRepository.findLatestCandidatesByProjectId(projectId)).thenReturn(List.of());
        when(sourceUploadVersionRepository.findTopBySourceProjectIdOrderByVersionNoDesc(projectId)).thenReturn(Optional.empty());
        when(sourceAnalysisResultRepository.save(any(SourceAnalysisResult.class))).thenReturn(analysisResult);

        // Make the parser throw an exception
        when(apiMetadataParserService.parseProject(projectId, false))
                .thenThrow(new BadRequestException("Parser failure simulated"));

        // Run service method - should NOT throw exception
        SourceAnalysisResultDetailResponse response = service.analyzeProject(projectId);

        // Verification
        assertThat(response).isNotNull();
        verify(apiMetadataParserService).parseProject(projectId, false);
    }
}
