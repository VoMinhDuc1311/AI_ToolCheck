package com.aitoolcheck.ai_toolcheck1_backend.service.legacy;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiInferenceResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LegacyRuleBasedEndpointExtractorService {

    private static final Pattern CLASS_PATTERN = Pattern.compile("\\bclass\\s+([A-Za-z_$][\\w$]*)\\b");
    private static final Pattern ACTION_CASE_PATTERN = Pattern.compile("\\bcase\\s+\"([A-Za-z0-9_-]+)\"\\s*:");
    private static final List<String> HELPER_NAME_PATTERNS = List.of(
            "dbbridge", "dao", "repository", "mapper", "util", "utils", "helper",
            "writer", "dto", "entity", "model", "config", "configuration"
    );

    public AiInferenceResultDto extract(SourceFile sourceFile) {
        String source = sourceFile != null && sourceFile.getSourceContent() != null
                ? sourceFile.getSourceContent()
                : "";
        String fileName = sourceFile != null ? sourceFile.getFileName() : null;
        String filePath = sourceFile != null ? sourceFile.getFilePath() : null;
        return extract(fileName, filePath, source, null);
    }

    public AiInferenceResultDto extract(String fileName, String filePath, String sourceContent, String candidateType) {
        String source = sourceContent == null ? "" : sourceContent;
        String className = detectClassName(source, fileName);

        AiInferenceResultDto result = new AiInferenceResultDto();
        if (source.isBlank() || isHelperLike(className, fileName, filePath, candidateType)) {
            result.setEndpoints(List.of());
            return result;
        }

        Map<String, AiInferenceResultDto.EndpointDto> endpoints = new LinkedHashMap<>();

        if (isServletEntrypoint(source)) {
            addServletEndpoint(endpoints, className, source, "doGet", "GET");
            addServletEndpoint(endpoints, className, source, "doPost", "POST");
            addServletEndpoint(endpoints, className, source, "doPut", "PUT");
            addServletEndpoint(endpoints, className, source, "doDelete", "DELETE");
            if (endpoints.isEmpty() && containsMethod(source, "service")) {
                addEndpoint(endpoints, className, "service", "POST", "/" + className, 0.55);
            }
        }

        if (isActionEntrypoint(className, source)) {
            String actionPath = "/legacy/" + toKebabCase(stripActionSuffix(className));
            addEndpoint(endpoints, className, "execute", "POST", actionPath, 0.65);
        }

        if (!endpoints.isEmpty()) {
            addSimpleActionSubpaths(endpoints, className, source);
        }

        result.setEndpoints(new ArrayList<>(endpoints.values()));
        return result;
    }

    private String detectClassName(String source, String fileName) {
        Matcher matcher = CLASS_PATTERN.matcher(source);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (fileName == null || fileName.isBlank()) {
            return "LegacyEntrypoint";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private boolean isHelperLike(String className, String fileName, String filePath, String candidateType) {
        if (candidateType != null) {
            String type = candidateType.toUpperCase(Locale.ROOT);
            if ("HELPER".equals(type) || "UNKNOWN".equals(type)) {
                return true;
            }
        }

        String combined = (safe(className) + " " + safe(fileName) + " " + safe(filePath)).toLowerCase(Locale.ROOT);
        for (String pattern : HELPER_NAME_PATTERNS) {
            if (combined.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean isServletEntrypoint(String source) {
        return source.contains("extends HttpServlet")
                || source.contains("HttpServletRequest")
                || source.contains("HttpServletResponse")
                || containsMethod(source, "doGet")
                || containsMethod(source, "doPost")
                || containsMethod(source, "doPut")
                || containsMethod(source, "doDelete")
                || containsMethod(source, "service");
    }

    private boolean isActionEntrypoint(String className, String source) {
        return className.endsWith("Action") || containsMethod(source, "execute");
    }

    private void addServletEndpoint(Map<String, AiInferenceResultDto.EndpointDto> endpoints,
                                    String className,
                                    String source,
                                    String javaMethod,
                                    String httpMethod) {
        if (containsMethod(source, javaMethod)) {
            addEndpoint(endpoints, className, javaMethod, httpMethod, "/" + className, 0.7);
        }
    }

    private void addSimpleActionSubpaths(Map<String, AiInferenceResultDto.EndpointDto> endpoints,
                                         String className,
                                         String source) {
        boolean hasActionParameter = source.contains("getParameter(\"action\"")
                || source.contains("getParameter(\"type\"");
        boolean hasSwitch = source.contains("switch(action)") || source.contains("switch (action)")
                || source.contains("switch(type)") || source.contains("switch (type)");
        if (!hasActionParameter || !hasSwitch) {
            return;
        }

        Matcher matcher = ACTION_CASE_PATTERN.matcher(source);
        while (matcher.find()) {
            String action = matcher.group(1);
            if (action.isBlank()) {
                continue;
            }
            String basePath = className.endsWith("Action")
                    ? "/legacy/" + toKebabCase(stripActionSuffix(className))
                    : "/" + className;
            addEndpoint(endpoints, className, "dispatch" + capitalize(action), "POST",
                    basePath + "/" + action, 0.6);
        }
    }

    private void addEndpoint(Map<String, AiInferenceResultDto.EndpointDto> endpoints,
                             String className,
                             String methodName,
                             String httpMethod,
                             String path,
                             double confidence) {
        String key = httpMethod + " " + path;
        endpoints.computeIfAbsent(key, ignored -> {
            AiInferenceResultDto.EndpointDto endpoint = new AiInferenceResultDto.EndpointDto();
            endpoint.setHttpMethod(httpMethod);
            endpoint.setPath(path);
            endpoint.setDescription("Rule-based fallback endpoint inferred from legacy Java entrypoint " + className + ".");
            endpoint.setAuthRequired(false);
            endpoint.setParameters(List.of());
            endpoint.setResponses(List.of(defaultResponse()));
            endpoint.setConfidence(confidence);

            AiInferenceResultDto.SourceDto source = new AiInferenceResultDto.SourceDto();
            source.setClassName(className);
            source.setMethodName(methodName);
            endpoint.setSource(source);
            return endpoint;
        });
    }

    private AiInferenceResultDto.ResponseDto defaultResponse() {
        AiInferenceResultDto.ResponseDto response = new AiInferenceResultDto.ResponseDto();
        response.setStatusCode(200);
        response.setContentType("application/json");
        return response;
    }

    private boolean containsMethod(String source, String methodName) {
        return Pattern.compile("\\b(public|protected|private)\\s+[\\w<>\\[\\].]+\\s+" + methodName + "\\s*\\(")
                .matcher(source)
                .find();
    }

    private String stripActionSuffix(String className) {
        return className.endsWith("Action") ? className.substring(0, className.length() - "Action".length()) : className;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String toKebabCase(String value) {
        if (value == null || value.isBlank()) {
            return "legacy-entrypoint";
        }
        String withDashes = value.trim()
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return withDashes.toLowerCase(Locale.ROOT);
    }
}
