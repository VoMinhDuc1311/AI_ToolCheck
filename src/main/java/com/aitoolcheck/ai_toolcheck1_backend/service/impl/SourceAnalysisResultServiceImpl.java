package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceanalysisresult.res.SourceAnalysisResultDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationSeverity;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.SourceStyle;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceAnalysisResult;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceUploadVersion;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceAnalysisResultRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceFileRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceUploadVersionRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceAnalysisResultService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.analysis.AnalysisSignals;
import com.aitoolcheck.ai_toolcheck1_backend.service.analysis.JavaSourceAnalyzer;
import com.aitoolcheck.ai_toolcheck1_backend.service.analysis.SourceAnalysisDecisionService;
import com.aitoolcheck.ai_toolcheck1_backend.service.analysis.SourceAnalysisScoringService;
import com.aitoolcheck.ai_toolcheck1_backend.service.analysis.SourceAnalysisSummaryBuilder;
import com.aitoolcheck.ai_toolcheck1_backend.service.notification.ProjectNotificationEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SourceAnalysisResultServiceImpl implements SourceAnalysisResultService {

    private final SourceAnalysisResultRepository sourceAnalysisResultRepository;
    private final SourceFileRepository sourceFileRepository;
    private final SourceProjectRepository sourceProjectRepository;
    private final SourceUploadVersionRepository sourceUploadVersionRepository;
    private final JavaSourceAnalyzer javaSourceAnalyzer;
    private final SourceAnalysisScoringService scoringService;
    private final SourceAnalysisDecisionService decisionService;
    private final SourceAnalysisSummaryBuilder summaryBuilder;
    private final ProjectAccessService projectAccessService;
    private final ProjectNotificationEventPublisher notificationEventPublisher;

    @Override
    @Transactional
    public SourceAnalysisResultDetailResponse analyzeProject(UUID projectId) {
        SourceProject sourceProject = projectAccessService.requireCanTriggerAiJob(projectId);

        sourceProject.setStatus(ProjectStatus.ANALYZING);
        sourceProjectRepository.save(sourceProject);

        List<SourceFile> sourceFiles = sourceFileRepository.findBySourceProjectIdAndActiveFlagTrue(projectId);

        if (sourceFiles.isEmpty()) {
            throw new BadRequestException("No source files found for project id: " + projectId);
        }

        List<SourceFile> analyzableFiles = sourceFiles.stream()
                .filter(file -> file.getSourceContent() != null && !file.getSourceContent().isBlank())
                .toList();

        if (analyzableFiles.isEmpty()) {
            throw new BadRequestException("No source content found for project id: " + projectId);
        }

        AnalysisSignals signals = javaSourceAnalyzer.analyze(projectId, analyzableFiles);
        sourceFileRepository.saveAll(analyzableFiles);

        double parseSuccessRate = scoringService.calculateRate(signals.parsedSuccessFiles(), analyzableFiles.size());
        int annotationScore = scoringService.calculateAnnotationScore(signals);
        int structureScore = scoringService.calculateStructureScore(analyzableFiles, parseSuccessRate);

        SourceStyle sourceStyle = decisionService.determineSourceStyle(signals, parseSuccessRate, annotationScore, structureScore);
        boolean parserRecommended = decisionService.determineParserRecommended(parseSuccessRate, annotationScore, structureScore);
        boolean aiRecommended = decisionService.determineAiRecommended(
                sourceStyle,
                parseSuccessRate,
                annotationScore,
                structureScore,
                signals.parsedFailedFiles(),
                analyzableFiles.size()
        );
        String summary = summaryBuilder.buildSummary(sourceStyle, parserRecommended, aiRecommended);

        SourceAnalysisResult analysisResult = findLatestAnalysisResult(projectId)
                .orElse(SourceAnalysisResult.builder()
                        .sourceProject(sourceProject)
                        .build());

        analysisResult.setSourceStyle(sourceStyle);
        analysisResult.setAnnotationScore(annotationScore);
        analysisResult.setStructureScore(structureScore);
        analysisResult.setParserRecommended(parserRecommended);
        analysisResult.setAiRecommended(aiRecommended);
        analysisResult.setTotalFiles(sourceFiles.size());
        analysisResult.setAnalyzableFiles(analyzableFiles.size());
        analysisResult.setParsedSuccessFiles(signals.parsedSuccessFiles());
        analysisResult.setParsedFailedFiles(signals.parsedFailedFiles());
        analysisResult.setParseSuccessRate(parseSuccessRate);
        analysisResult.setSummary(summary);
        analysisResult.setCurrentFlag(Boolean.TRUE);
        sourceUploadVersionRepository.findTopBySourceProjectIdOrderByVersionNoDesc(projectId)
                .ifPresent(analysisResult::setSourceUploadVersion);

        SourceAnalysisResult savedResult = sourceAnalysisResultRepository.save(analysisResult);

        sourceProject.setStatus(ProjectStatus.ANALYZED);
        sourceProjectRepository.save(sourceProject);

        notificationEventPublisher.publishForCurrentUser(
                projectId,
                NotificationType.SOURCE_ANALYSIS_COMPLETED,
                NotificationSeverity.SUCCESS,
                "Source analysis completed",
                "Source analysis completed for " + sourceProject.getProjectName() + ".",
                "/source-projects/" + projectId,
                Map.of(
                        "projectId", projectId,
                        "analysisResultId", savedResult.getId(),
                        "status", ProjectStatus.ANALYZED.name()
                ));

        return mapToDetailResponse(savedResult);
    }

    @Override
    @Transactional(readOnly = true)
    public SourceAnalysisResultDetailResponse getByProjectId(UUID projectId) {
        projectAccessService.requireCanViewProject(projectId);

        SourceAnalysisResult result = findLatestAnalysisResult(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Source analysis result not found for project id: " + projectId));

        return mapToDetailResponse(result);
    }

    private java.util.Optional<SourceAnalysisResult> findLatestAnalysisResult(UUID projectId) {
        return sourceAnalysisResultRepository.findLatestCandidatesByProjectId(projectId)
                .stream()
                .findFirst();
    }

    private SourceAnalysisResultDetailResponse mapToDetailResponse(SourceAnalysisResult result) {
        SourceUploadVersion uploadVersion = result.getSourceUploadVersion();

        return SourceAnalysisResultDetailResponse.builder()
                .id(result.getId())
                .projectId(result.getSourceProject().getId())
                .sourceStyle(result.getSourceStyle())
                .annotationScore(result.getAnnotationScore())
                .structureScore(result.getStructureScore())
                .parserRecommended(result.getParserRecommended())
                .aiRecommended(result.getAiRecommended())
                .totalFiles(result.getTotalFiles())
                .analyzableFiles(result.getAnalyzableFiles())
                .parsedSuccessFiles(result.getParsedSuccessFiles())
                .parsedFailedFiles(result.getParsedFailedFiles())
                .parsedFailed(result.getParsedFailedFiles())
                .parseSuccessRate(result.getParseSuccessRate())
                .summary(result.getSummary())
                .currentFlag(result.getCurrentFlag())
                .sourceUploadVersionId(uploadVersion == null ? null : uploadVersion.getId())
                .latestUploadVersionId(uploadVersion == null ? null : uploadVersion.getId())
                .versionNo(uploadVersion == null ? null : uploadVersion.getVersionNo())
                .originalFileName(uploadVersion == null ? null : uploadVersion.getOriginalFileName())
                .totalJavaFilesFound(uploadVersion == null ? null : uploadVersion.getTotalJavaFilesFound())
                .savedFiles(uploadVersion == null ? null : uploadVersion.getSavedFiles())
                .ignoredFiles(uploadVersion == null ? null : uploadVersion.getIgnoredFiles())
                .addedFiles(uploadVersion == null ? null : uploadVersion.getAddedFiles())
                .updatedFiles(uploadVersion == null ? null : uploadVersion.getUpdatedFiles())
                .unchangedFiles(uploadVersion == null ? null : uploadVersion.getUnchangedFiles())
                .deletedFiles(uploadVersion == null ? null : uploadVersion.getDeletedFiles())
                .uploadStatus(uploadVersion == null || uploadVersion.getStatus() == null ? null : uploadVersion.getStatus().name())
                .uploadCreatedAt(uploadVersion == null ? null : uploadVersion.getCreatedAt())
                .completedAt(uploadVersion == null ? null : uploadVersion.getCompletedAt())
                .build();
    }
}
