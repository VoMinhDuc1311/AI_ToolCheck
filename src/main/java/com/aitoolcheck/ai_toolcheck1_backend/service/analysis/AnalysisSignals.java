package com.aitoolcheck.ai_toolcheck1_backend.service.analysis;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AnalysisSignals {

    private int parsedSuccessFiles;
    private int parsedFailedFiles;
    private boolean hasControllerAnnotation;
    private boolean hasMappingAnnotation;
    private boolean hasServiceAnnotation;
    private boolean hasRepositoryAnnotation;
    private boolean hasEntityAnnotation;
    private boolean hasDependencyInjectionAnnotation;
    private final Set<String> annotationGroups = new HashSet<>();

    public void accept(List<String> annotationNames) {
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

    public void incrementParsedSuccessFiles() {
        parsedSuccessFiles++;
    }

    public void incrementParsedFailedFiles() {
        parsedFailedFiles++;
    }

    public int parsedSuccessFiles() {
        return parsedSuccessFiles;
    }

    public int parsedFailedFiles() {
        return parsedFailedFiles;
    }

    public boolean hasControllerAnnotation() {
        return hasControllerAnnotation;
    }

    public boolean hasMappingAnnotation() {
        return hasMappingAnnotation;
    }

    public boolean hasServiceAnnotation() {
        return hasServiceAnnotation;
    }

    public boolean hasRepositoryAnnotation() {
        return hasRepositoryAnnotation;
    }

    public boolean hasEntityAnnotation() {
        return hasEntityAnnotation;
    }

    public boolean hasDependencyInjectionAnnotation() {
        return hasDependencyInjectionAnnotation;
    }

    public Set<String> annotationGroups() {
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
