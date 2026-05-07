package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceanalysisresult.res.SourceAnalysisResultDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.SourceStyle;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceAnalysisResult;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceAnalysisResultRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceFileRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceUploadVersionRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceAnalysisResultService;
import com.aitoolcheck.ai_toolcheck1_backend.service.analysis.AnalysisSignals;
import com.aitoolcheck.ai_toolcheck1_backend.service.analysis.JavaSourceAnalyzer;
import com.aitoolcheck.ai_toolcheck1_backend.service.analysis.SourceAnalysisDecisionService;
import com.aitoolcheck.ai_toolcheck1_backend.service.analysis.SourceAnalysisScoringService;
import com.aitoolcheck.ai_toolcheck1_backend.service.analysis.SourceAnalysisSummaryBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    @Override
    @Transactional
    public SourceAnalysisResultDetailResponse analyzeProject(UUID projectId) {
        SourceProject sourceProject = sourceProjectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Source project not found with id: " + projectId));

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

        SourceStyle sourceStyle = decisionService.determineSourceStyle(parseSuccessRate, annotationScore, structureScore);
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

        SourceAnalysisResult analysisResult = sourceAnalysisResultRepository.findBySourceProjectId(projectId)
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

        return mapToDetailResponse(savedResult);
    }

    @Override
    public SourceAnalysisResultDetailResponse getByProjectId(UUID projectId) {
        if (!sourceProjectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Source project not found with id: " + projectId);
        }

        SourceAnalysisResult result = sourceAnalysisResultRepository.findBySourceProjectId(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Source analysis result not found for project id: " + projectId));

        return mapToDetailResponse(result);
    }

    private SourceAnalysisResultDetailResponse mapToDetailResponse(SourceAnalysisResult result) {
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
                .parseSuccessRate(result.getParseSuccessRate())
                .summary(result.getSummary())
                .currentFlag(result.getCurrentFlag())
                .sourceUploadVersionId(result.getSourceUploadVersion() == null ? null : result.getSourceUploadVersion().getId())
                .build();
    }
}
