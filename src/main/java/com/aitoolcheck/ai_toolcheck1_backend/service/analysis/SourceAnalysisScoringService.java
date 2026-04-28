package com.aitoolcheck.ai_toolcheck1_backend.service.analysis;

import com.aitoolcheck.ai_toolcheck1_backend.enums.FileType;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SourceAnalysisScoringService {

    public int calculateAnnotationScore(AnalysisSignals signals) {
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

    public int calculateStructureScore(List<SourceFile> files, double parseSuccessRate) {
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

    public double calculateRate(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return (double) numerator / denominator;
    }
}
