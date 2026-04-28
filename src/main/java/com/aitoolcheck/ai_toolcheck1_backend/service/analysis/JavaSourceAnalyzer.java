package com.aitoolcheck.ai_toolcheck1_backend.service.analysis;

import com.aitoolcheck.ai_toolcheck1_backend.enums.FileType;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.AnnotationExpr;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class JavaSourceAnalyzer {

    private static final int MAX_PARSE_ERROR_LENGTH = 1000;

    private final SourceFileClassificationService sourceFileClassificationService;

    public AnalysisSignals analyze(UUID projectId, List<SourceFile> analyzableFiles) {
        AnalysisSignals signals = new AnalysisSignals();

        for (SourceFile sourceFile : analyzableFiles) {
            try {
                CompilationUnit compilationUnit = StaticJavaParser.parse(sourceFile.getSourceContent());
                List<String> annotationNames = compilationUnit.findAll(AnnotationExpr.class)
                        .stream()
                        .map(AnnotationExpr::getNameAsString)
                        .toList();

                signals.accept(annotationNames);

                FileType detectedType = sourceFileClassificationService.detectFileType(sourceFile, compilationUnit, annotationNames);
                if (detectedType != FileType.UNKNOWN) {
                    sourceFile.setFileType(detectedType);
                }

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

    private String shorten(String message) {
        String normalized = (message == null || message.isBlank())
                ? "Unknown parse error"
                : message.replace('\n', ' ').replace('\r', ' ').trim();

        if (normalized.length() <= MAX_PARSE_ERROR_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_PARSE_ERROR_LENGTH);
    }
}
