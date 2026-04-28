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
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SourceAnalysisResultServiceImpl implements SourceAnalysisResultService {

    private static final int MAX_PARSE_ERROR_LENGTH = 1000;

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

        AnalysisSignals signals = analyzeFiles(projectId, analyzableFiles);
        sourceFileRepository.saveAll(analyzableFiles);

        double parseSuccessRate = calculateRate(signals.parsedSuccessFiles(), analyzableFiles.size());
        int annotationScore = calculateAnnotationScore(signals);
        int structureScore = calculateStructureScore(analyzableFiles, parseSuccessRate);

        SourceStyle sourceStyle = determineSourceStyle(parseSuccessRate, annotationScore, structureScore);
        boolean parserRecommended = determineParserRecommended(parseSuccessRate, annotationScore, structureScore);
        boolean aiRecommended = determineAiRecommended(
                sourceStyle,
                parseSuccessRate,
                annotationScore,
                structureScore,
                signals.parsedFailedFiles(),
                analyzableFiles.size()
        );
        String summary = buildSummary(sourceStyle, parserRecommended, aiRecommended);

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

    private AnalysisSignals analyzeFiles(UUID projectId, List<SourceFile> analyzableFiles) {
        AnalysisSignals signals = new AnalysisSignals();

        for (SourceFile sourceFile : analyzableFiles) {
            try {
                CompilationUnit compilationUnit = StaticJavaParser.parse(sourceFile.getSourceContent());
                List<String> annotationNames = compilationUnit.findAll(AnnotationExpr.class)
                        .stream()
                        .map(AnnotationExpr::getNameAsString)
                        .toList();

                signals.accept(annotationNames);
                updateFileTypeFromParsedSource(sourceFile, compilationUnit, annotationNames);

                sourceFile.setParsedFlag(Boolean.TRUE);
                sourceFile.setParseError(null);
                signals.incrementParsedSuccessFiles();
            } catch (Exception e) {
                String parseError = shorten(e.getMessage());
                sourceFile.setParsedFlag(Boolean.FALSE);
                sourceFile.setParseError(parseError);
                signals.incrementParsedFailedFiles();

                log.warn(
                        "JavaParser failed. projectId={}, sourceFileId={}, fileName={}, filePath={}, reason={}",
                        projectId,
                        sourceFile.getId(),
                        sourceFile.getFileName(),
                        sourceFile.getFilePath(),
                        parseError
                );
            }
        }

        return signals;
    }

    private void updateFileTypeFromParsedSource(
            SourceFile sourceFile,
            CompilationUnit compilationUnit,
            List<String> annotationNames
    ) {
        FileType detectedType = detectFileTypeFromAst(sourceFile, compilationUnit, annotationNames);

        if (detectedType != FileType.UNKNOWN) {
            sourceFile.setFileType(detectedType);
        }
    }

    private FileType detectFileTypeFromAst(
            SourceFile sourceFile,
            CompilationUnit compilationUnit,
            List<String> annotationNames
    ) {
        String path = normalizedPath(sourceFile);
        String name = normalizedName(sourceFile);
        List<ClassOrInterfaceDeclaration> classes = compilationUnit.findAll(ClassOrInterfaceDeclaration.class);

        if (hasAny(annotationNames, "SpringBootApplication")) {
            return FileType.APPLICATION;
        }
        if (hasAny(annotationNames, "RestController", "Controller", "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping")) {
            return FileType.CONTROLLER;
        }
        if (hasAny(annotationNames, "RestControllerAdvice", "ControllerAdvice", "ExceptionHandler")) {
            return FileType.EXCEPTION_HANDLER;
        }
        if (hasAny(annotationNames, "EnableWebSecurity", "PreAuthorize")
                || path.contains("/security/")
                || path.contains("/auth/")
                || name.contains("security")
                || name.contains("jwt")
                || name.contains("token")) {
            return FileType.SECURITY;
        }
        if (extendsOrImplements(classes, "OncePerRequestFilter") || extendsOrImplements(classes, "Filter")) {
            return FileType.FILTER;
        }
        if (extendsOrImplements(classes, "HandlerInterceptor")) {
            return FileType.INTERCEPTOR;
        }
        if (path.contains("/service/impl/") || name.endsWith("serviceimpl.java")) {
            return FileType.SERVICE_IMPL;
        }
        if (hasAny(annotationNames, "Service") || path.contains("/service/")) {
            return FileType.SERVICE;
        }
        if (hasAny(annotationNames, "Repository")
                || extendsOrImplements(classes, "JpaRepository")
                || extendsOrImplements(classes, "CrudRepository")
                || extendsOrImplements(classes, "PagingAndSortingRepository")
                || extendsOrImplements(classes, "MongoRepository")
                || path.contains("/repository/")
                || path.contains("/respository/")
                || name.contains("repository")) {
            return FileType.REPOSITORY;
        }
        if (hasAny(annotationNames, "Entity", "Table", "MappedSuperclass", "Embeddable")) {
            return FileType.ENTITY;
        }
        if (path.contains("/request/") || path.contains("/req/") || name.endsWith("request.java")) {
            return FileType.REQUEST;
        }
        if (path.contains("/response/") || path.contains("/res/") || name.endsWith("response.java")) {
            return FileType.RESPONSE;
        }
        if (path.contains("/dto/") || name.contains("dto")) {
            return FileType.DTO;
        }
        if (!compilationUnit.findAll(EnumDeclaration.class).isEmpty()) {
            return FileType.ENUM;
        }
        if (!compilationUnit.findAll(AnnotationDeclaration.class).isEmpty()) {
            return FileType.ANNOTATION;
        }
        if (isInterfaceOnly(classes)) {
            return FileType.INTERFACE;
        }
        if (hasAny(annotationNames, "Configuration", "Bean")) {
            return FileType.CONFIG;
        }
        if (extendsOrImplements(classes, "ConstraintValidator") || name.contains("validator")) {
            return FileType.VALIDATOR;
        }
        if (hasAny(annotationNames, "Mapper") || path.contains("/mapper/") || name.contains("mapper")) {
            return FileType.MAPPER;
        }
        if (extendsOrImplements(classes, "RuntimeException") || extendsOrImplements(classes, "Exception")) {
            return FileType.EXCEPTION;
        }
        if (hasAny(annotationNames, "Scheduled") || name.contains("scheduler") || name.contains("job") || name.contains("task")) {
            return FileType.SCHEDULER;
        }
        if (hasAny(annotationNames, "EventListener") || name.contains("listener")) {
            return FileType.LISTENER;
        }
        if (name.endsWith("event.java")) {
            return FileType.EVENT;
        }
        if (extendsOrImplements(classes, "CommandLineRunner") || extendsOrImplements(classes, "ApplicationRunner")) {
            return FileType.COMMAND;
        }
        if (name.contains("constant") || name.contains("constants") || name.contains("errorcode")) {
            return FileType.CONSTANT;
        }
        if (path.contains("/util/") || path.contains("/utils/") || name.contains("util") || name.contains("helper")) {
            return FileType.UTIL;
        }
        if (path.startsWith("src/test/")
                || path.contains("/src/test/")
                || name.endsWith("test.java")
                || name.endsWith("tests.java")
                || hasAny(annotationNames, "Test", "SpringBootTest", "WebMvcTest", "DataJpaTest")) {
            return FileType.TEST;
        }
        if (path.contains("/model/")) {
            return FileType.MODEL;
        }

        return FileType.UNKNOWN;
    }

    private int calculateAnnotationScore(AnalysisSignals signals) {
        int score = 0;

        if (signals.hasControllerAnnotation()) {
            score += 25;
        }
        if (signals.hasMappingAnnotation()) {
            score += 30;
        }
        if (signals.hasServiceAnnotation()) {
            score += 10;
        }
        if (signals.hasRepositoryAnnotation()) {
            score += 10;
        }
        if (signals.hasEntityAnnotation()) {
            score += 10;
        }
        if (signals.hasDependencyInjectionAnnotation()) {
            score += 5;
        }
        if (signals.annotationGroups().size() >= 3) {
            score += 10;
        }

        return Math.min(score, 100);
    }

    private int calculateStructureScore(List<SourceFile> files, double parseSuccessRate) {
        int score = 0;

        boolean hasController = files.stream().anyMatch(file -> file.getFileType() == FileType.CONTROLLER);
        boolean hasService = files.stream().anyMatch(file -> file.getFileType() == FileType.SERVICE || file.getFileType() == FileType.SERVICE_IMPL);
        boolean hasRepository = files.stream().anyMatch(file -> file.getFileType() == FileType.REPOSITORY);
        boolean hasModel = files.stream().anyMatch(file -> file.getFileType() == FileType.MODEL || file.getFileType() == FileType.ENTITY);
        boolean hasDto = files.stream().anyMatch(file -> file.getFileType() == FileType.DTO || file.getFileType() == FileType.REQUEST || file.getFileType() == FileType.RESPONSE);
        boolean hasConfig = files.stream().anyMatch(file -> file.getFileType() == FileType.CONFIG || file.getFileType() == FileType.APPLICATION);
        boolean hasEnum = files.stream().anyMatch(file -> file.getFileType() == FileType.ENUM);

        if (hasController) score += 20;
        if (hasService) score += 15;
        if (hasRepository) score += 15;
        if (hasModel) score += 15;
        if (hasDto) score += 10;
        if (hasConfig) score += 5;
        if (hasEnum) score += 5;

        long packageCount = files.stream()
                .filter(file -> file.getPackageName() != null && !file.getPackageName().isBlank())
                .count();

        double packageRate = calculateRate((int) packageCount, files.size());
        if (packageRate >= 0.70) {
            score += 10;
        }

        if (parseSuccessRate >= 0.80) {
            score += 15;
        }

        return Math.min(score, 100);
    }

    private SourceStyle determineSourceStyle(double parseSuccessRate, int annotationScore, int structureScore) {
        if (parseSuccessRate >= 0.70 && annotationScore >= 50 && structureScore >= 50) {
            return SourceStyle.MODERN;
        }
        return SourceStyle.LEGACY;
    }

    private boolean determineParserRecommended(double parseSuccessRate, int annotationScore, int structureScore) {
        return parseSuccessRate >= 0.70 && annotationScore >= 50 && structureScore >= 50;
    }

    private boolean determineAiRecommended(
            SourceStyle sourceStyle,
            double parseSuccessRate,
            int annotationScore,
            int structureScore,
            int parsedFailedFiles,
            int analyzableFiles
    ) {
        double parseFailureRate = calculateRate(parsedFailedFiles, analyzableFiles);

        return parseSuccessRate < 0.70
                || annotationScore < 40
                || structureScore < 40
                || sourceStyle == SourceStyle.LEGACY
                || (parsedFailedFiles > 0 && parseFailureRate >= 0.20);
    }

    private String buildSummary(SourceStyle sourceStyle, boolean parserRecommended, boolean aiRecommended) {
        if (sourceStyle == SourceStyle.MODERN && parserRecommended && !aiRecommended) {
            return "Modern Spring project. Parser recommended because annotation and structure scores are high.";
        }
        if (sourceStyle == SourceStyle.MODERN && parserRecommended) {
            return "Modern Spring project with mixed parse confidence. Parser can be used, but AI fallback is also recommended.";
        }
        return "Legacy or weakly structured project. AI fallback recommended because parse success rate or scores are low.";
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
                .build();
    }

    private boolean hasAny(List<String> annotationNames, String... candidates) {
        for (String candidate : candidates) {
            if (annotationNames.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean extendsOrImplements(List<ClassOrInterfaceDeclaration> classes, String typeName) {
        return classes.stream().anyMatch(declaration ->
                declaration.getExtendedTypes().stream().anyMatch(type -> type.getNameAsString().equals(typeName))
                        || declaration.getImplementedTypes().stream().anyMatch(type -> type.getNameAsString().equals(typeName))
        );
    }

    private boolean isInterfaceOnly(List<ClassOrInterfaceDeclaration> classes) {
        return !classes.isEmpty() && classes.stream().allMatch(ClassOrInterfaceDeclaration::isInterface);
    }

    private String normalizedPath(SourceFile sourceFile) {
        if (sourceFile.getFilePath() == null) {
            return "";
        }
        return sourceFile.getFilePath().replace("\\", "/").toLowerCase();
    }

    private String normalizedName(SourceFile sourceFile) {
        if (sourceFile.getFileName() == null) {
            return "";
        }
        return sourceFile.getFileName().toLowerCase();
    }

    private double calculateRate(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return (double) numerator / denominator;
    }

    private String shorten(String message) {
        String normalized = (message == null || message.isBlank())
                ? "Unknown parse error"
                : message.replace('\n', ' ').replace('\r', ' ').trim();

        if (normalized.length() <= MAX_PARSE_ERROR_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_PARSE_ERROR_LENGTH);
    }

    private static class AnalysisSignals {

        private int parsedSuccessFiles;
        private int parsedFailedFiles;
        private boolean hasControllerAnnotation;
        private boolean hasMappingAnnotation;
        private boolean hasServiceAnnotation;
        private boolean hasRepositoryAnnotation;
        private boolean hasEntityAnnotation;
        private boolean hasDependencyInjectionAnnotation;
        private final Set<String> annotationGroups = new HashSet<>();

        void accept(List<String> annotationNames) {
            if (containsAny(annotationNames, "RestController", "Controller")) {
                hasControllerAnnotation = true;
                annotationGroups.add("controller");
            }
            if (containsAny(annotationNames, "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping")) {
                hasMappingAnnotation = true;
                annotationGroups.add("mapping");
            }
            if (containsAny(annotationNames, "Service")) {
                hasServiceAnnotation = true;
                annotationGroups.add("service");
            }
            if (containsAny(annotationNames, "Repository")) {
                hasRepositoryAnnotation = true;
                annotationGroups.add("repository");
            }
            if (containsAny(annotationNames, "Entity", "Table")) {
                hasEntityAnnotation = true;
                annotationGroups.add("entity");
            }
            if (containsAny(annotationNames, "Autowired", "RequiredArgsConstructor", "AllArgsConstructor")) {
                hasDependencyInjectionAnnotation = true;
                annotationGroups.add("dependencyInjection");
            }
        }

        void incrementParsedSuccessFiles() {
            parsedSuccessFiles++;
        }

        void incrementParsedFailedFiles() {
            parsedFailedFiles++;
        }

        int parsedSuccessFiles() {
            return parsedSuccessFiles;
        }

        int parsedFailedFiles() {
            return parsedFailedFiles;
        }

        boolean hasControllerAnnotation() {
            return hasControllerAnnotation;
        }

        boolean hasMappingAnnotation() {
            return hasMappingAnnotation;
        }

        boolean hasServiceAnnotation() {
            return hasServiceAnnotation;
        }

        boolean hasRepositoryAnnotation() {
            return hasRepositoryAnnotation;
        }

        boolean hasEntityAnnotation() {
            return hasEntityAnnotation;
        }

        boolean hasDependencyInjectionAnnotation() {
            return hasDependencyInjectionAnnotation;
        }

        Set<String> annotationGroups() {
            return annotationGroups;
        }

        private static boolean containsAny(List<String> annotationNames, String... candidates) {
            for (String candidate : candidates) {
                if (annotationNames.contains(candidate)) {
                    return true;
                }
            }
            return false;
        }
    }
}
