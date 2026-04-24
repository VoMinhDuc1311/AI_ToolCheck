package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceanalysisresult.res.SourceAnalysisResultDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.FileType;
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
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceAnalysisResultService;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.AnnotationExpr;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SourceAnalysisResultServiceImpl implements SourceAnalysisResultService {

    private final SourceAnalysisResultRepository sourceAnalysisResultRepository;
    private final SourceFileRepository sourceFileRepository;
    private final SourceProjectRepository sourceProjectRepository;

    @Override
    @Transactional
    public SourceAnalysisResultDetailResponse analyzeProject(UUID projectId) {
        SourceProject sourceProject = sourceProjectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Source project not found with id: " + projectId));

        List<SourceFile> sourceFiles = sourceFileRepository.findBySourceProjectId(projectId);

        if (sourceFiles.isEmpty()) {
            throw new BadRequestException("No source files found for project id: " + projectId);
        }

        List<SourceFile> analyzableFiles = sourceFiles.stream()
                .filter(file -> file.getSourceContent() != null && !file.getSourceContent().isBlank())
                .toList();

        if (analyzableFiles.isEmpty()) {
            throw new BadRequestException("No source content found for project id: " + projectId);
        }

        int parsedSuccess = 0;
        int parsedFailed = 0;

        int restControllerCount = 0;
        int controllerCount = 0;
        int serviceCount = 0;
        int repositoryCount = 0;
        int entityCount = 0;
        int tableCount = 0;
        int requestMappingCount = 0;
        int getMappingCount = 0;
        int postMappingCount = 0;
        int putMappingCount = 0;
        int deleteMappingCount = 0;
        int autowiredCount = 0;

        for (SourceFile sourceFile : analyzableFiles) {
            try {
                CompilationUnit compilationUnit = StaticJavaParser.parse(sourceFile.getSourceContent());

                List<AnnotationExpr> annotations = compilationUnit.findAll(AnnotationExpr.class);

                for (AnnotationExpr annotation : annotations) {
                    String name = annotation.getNameAsString();

                    switch (name) {
                        case "RestController" -> restControllerCount++;
                        case "Controller" -> controllerCount++;
                        case "Service" -> serviceCount++;
                        case "Repository" -> repositoryCount++;
                        case "Entity" -> entityCount++;
                        case "Table" -> tableCount++;
                        case "RequestMapping" -> requestMappingCount++;
                        case "GetMapping" -> getMappingCount++;
                        case "PostMapping" -> postMappingCount++;
                        case "PutMapping" -> putMappingCount++;
                        case "DeleteMapping" -> deleteMappingCount++;
                        case "Autowired" -> autowiredCount++;
                    }
                }

                sourceFile.setParsedFlag(Boolean.TRUE);
                parsedSuccess++;

            } catch (Exception e) {
                sourceFile.setParsedFlag(Boolean.FALSE);
                parsedFailed++;
            }
        }

        sourceFileRepository.saveAll(analyzableFiles);

        int annotationScore = calculateAnnotationScore(
                restControllerCount,
                controllerCount,
                serviceCount,
                repositoryCount,
                entityCount,
                tableCount,
                requestMappingCount,
                getMappingCount,
                postMappingCount,
                putMappingCount,
                deleteMappingCount,
                autowiredCount
        );

        int structureScore = calculateStructureScore(analyzableFiles, parsedSuccess, parsedFailed);

        SourceStyle sourceStyle = determineSourceStyle(annotationScore, structureScore, parsedSuccess, parsedFailed);

        boolean parserRecommended = determineParserRecommended(annotationScore, structureScore, parsedSuccess, parsedFailed);
        boolean aiRecommended = !parserRecommended;

        SourceAnalysisResult analysisResult = sourceAnalysisResultRepository.findBySourceProjectId(projectId)
                .orElse(SourceAnalysisResult.builder()
                        .sourceProject(sourceProject)
                        .build());

        analysisResult.setSourceStyle(sourceStyle);
        analysisResult.setAnnotationScore(annotationScore);
        analysisResult.setStructureScore(structureScore);
        analysisResult.setParserRecommended(parserRecommended);
        analysisResult.setAiRecommended(aiRecommended);

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

    private int calculateAnnotationScore(
            int restControllerCount,
            int controllerCount,
            int serviceCount,
            int repositoryCount,
            int entityCount,
            int tableCount,
            int requestMappingCount,
            int getMappingCount,
            int postMappingCount,
            int putMappingCount,
            int deleteMappingCount,
            int autowiredCount
    ) {
        int score = 0;
        score += (restControllerCount + controllerCount) * 15;
        score += (requestMappingCount + getMappingCount + postMappingCount + putMappingCount + deleteMappingCount) * 10;
        score += serviceCount * 5;
        score += repositoryCount * 5;
        score += entityCount * 5;
        score += tableCount * 5;
        score += autowiredCount * 2;
        return Math.min(score, 100);
    }

    private int calculateStructureScore(List<SourceFile> files, int parsedSuccess, int parsedFailed) {
        int score = 0;

        boolean hasController = files.stream().anyMatch(file -> file.getFileType() == FileType.CONTROLLER);
        boolean hasService = files.stream().anyMatch(file -> file.getFileType() == FileType.SERVICE);
        boolean hasRepository = files.stream().anyMatch(file -> file.getFileType() == FileType.REPOSITORY);
        boolean hasModel = files.stream().anyMatch(file -> file.getFileType() == FileType.MODEL || file.getFileType() == FileType.ENTITY);
        boolean hasDto = files.stream().anyMatch(file -> file.getFileType() == FileType.DTO);

        if (hasController) score += 20;
        if (hasService) score += 15;
        if (hasRepository) score += 15;
        if (hasModel) score += 15;
        if (hasDto) score += 10;

        long packageCount = files.stream()
                .filter(file -> file.getPackageName() != null && !file.getPackageName().isBlank())
                .count();

        if (!files.isEmpty()) {
            double packageRate = (double) packageCount / files.size();
            if (packageRate >= 0.7) {
                score += 10;
            }
        }

        int total = parsedSuccess + parsedFailed;
        if (total > 0) {
            double parseSuccessRate = (double) parsedSuccess / total;
            if (parseSuccessRate >= 0.8) {
                score += 15;
            }
        }

        return Math.min(score, 100);
    }

    private SourceStyle determineSourceStyle(int annotationScore, int structureScore, int parsedSuccess, int parsedFailed) {
        int total = parsedSuccess + parsedFailed;
        double parseSuccessRate = total == 0 ? 0 : (double) parsedSuccess / total;

        if (parseSuccessRate >= 0.7 && (annotationScore >= 30 || structureScore >= 50)) {
            return SourceStyle.MODERN;
        }

        return SourceStyle.LEGACY;
    }

    private boolean determineParserRecommended(int annotationScore, int structureScore, int parsedSuccess, int parsedFailed) {
        int total = parsedSuccess + parsedFailed;
        if (total == 0) {
            return false;
        }

        double parseSuccessRate = (double) parsedSuccess / total;
        return parseSuccessRate >= 0.7 && (annotationScore >= 25 || structureScore >= 50);
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
                .build();
    }
}