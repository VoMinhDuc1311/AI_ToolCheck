package com.aitoolcheck.ai_toolcheck1_backend.service.analysis;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AnalysisSignals {

    private int parsedSuccessFiles;
    private int parsedFailedFiles;

    // Modern signals
    private boolean hasControllerAnnotation;
    private boolean hasRestControllerAnnotation;
    private boolean hasMappingAnnotation;
    private boolean hasComposedMappingAnnotation; // @GetMapping/@PostMapping/etc., NOT bare @RequestMapping
    
    // Legacy signals
    private boolean hasRequestMappingMethodAttribute; // @RequestMapping(method = RequestMethod.GET/POST/...)
    private boolean hasResponseBodyAnnotation;

    // Infrastructure signals (used by scoring)
    private boolean hasServiceAnnotation;
    private boolean hasRepositoryAnnotation;
    private boolean hasEntityAnnotation;
    private boolean hasDependencyInjectionAnnotation;
    private final Set<String> annotationGroups = new HashSet<>();

    // Must be called with the full AnnotationExpr list so member-value pairs can be inspected.
    public void accept(List<AnnotationExpr> annotations) {
        List<String> names = annotations.stream()
                .map(AnnotationExpr::getNameAsString)
                .toList();

        if (containsAny(names, "RestController")) {
            hasRestControllerAnnotation = true;
            hasControllerAnnotation = true;
            annotationGroups.add("controller");
        } else if (containsAny(names, "Controller")) {
            hasControllerAnnotation = true;
            annotationGroups.add("controller");
        }
        if (containsAny(names, "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping")) {
            hasMappingAnnotation = true;
            annotationGroups.add("mapping");
        }
        if (containsAny(names, "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping")) {
            hasComposedMappingAnnotation = true;
        }

        // Inspect member pairs to detect legacy @RequestMapping(method = RequestMethod.GET/POST/...)
        for (AnnotationExpr annotation : annotations) {
            String name = annotation.getNameAsString();
            if (("RequestMapping".equals(name) || name.endsWith(".RequestMapping"))
                    && annotation.isNormalAnnotationExpr()) {
                boolean hasMethodPair = annotation.asNormalAnnotationExpr().getPairs().stream()
                        .anyMatch(pair -> "method".equals(pair.getNameAsString()));
                if (hasMethodPair) {
                    hasRequestMappingMethodAttribute = true;
                    break;
                }
            }
        }

        if (containsAny(names, "ResponseBody")) {
            hasResponseBodyAnnotation = true;
        }
        if (containsAny(names, "Service")) {
            hasServiceAnnotation = true;
            annotationGroups.add("service");
        }
        if (containsAny(names, "Repository")) {
            hasRepositoryAnnotation = true;
            annotationGroups.add("repository");
        }
        if (containsAny(names, "Entity", "Table")) {
            hasEntityAnnotation = true;
            annotationGroups.add("entity");
        }
        if (containsAny(names, "Autowired", "RequiredArgsConstructor", "AllArgsConstructor")) {
            hasDependencyInjectionAnnotation = true;
            annotationGroups.add("dependencyInjection");
        }
    }

    // @RestController + composed mapping = unambiguous modern Spring Boot architecture.
    public boolean hasStrongModernSpringSignals() {
        return hasRestControllerAnnotation && hasComposedMappingAnnotation;
    }

    // @RequestMapping(method=...) or @Controller+@ResponseBody without @RestController/composed mappings.
    // Note: @Controller alone is NOT a legacy signal — it is still used for view-rendering controllers.
    public boolean hasStrongLegacySignals() {
        if (hasRequestMappingMethodAttribute) return true;
        return hasControllerAnnotation && !hasRestControllerAnnotation
                && hasResponseBodyAnnotation && !hasComposedMappingAnnotation;
    }

    public void incrementParsedSuccessFiles() { parsedSuccessFiles++; }
    public void incrementParsedFailedFiles()  { parsedFailedFiles++; }
    public int parsedSuccessFiles()           { return parsedSuccessFiles; }
    public int parsedFailedFiles()            { return parsedFailedFiles; }

    public boolean hasControllerAnnotation()          { return hasControllerAnnotation; }
    public boolean hasRestControllerAnnotation()      { return hasRestControllerAnnotation; }
    public boolean hasMappingAnnotation()             { return hasMappingAnnotation; }
    public boolean hasComposedMappingAnnotation()     { return hasComposedMappingAnnotation; }
    public boolean hasRequestMappingMethodAttribute() { return hasRequestMappingMethodAttribute; }
    public boolean hasResponseBodyAnnotation()        { return hasResponseBodyAnnotation; }
    public boolean hasServiceAnnotation()             { return hasServiceAnnotation; }
    public boolean hasRepositoryAnnotation()          { return hasRepositoryAnnotation; }
    public boolean hasEntityAnnotation()              { return hasEntityAnnotation; }
    public boolean hasDependencyInjectionAnnotation() { return hasDependencyInjectionAnnotation; }
    public Set<String> annotationGroups()             { return annotationGroups; }

    private static boolean containsAny(List<String> names, String... candidates) {
        for (String candidate : candidates) {
            if (names.contains(candidate)) return true;
        }
        return false;
    }
}
