package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiMetadataCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiMetadataCleanupServiceImpl implements ApiMetadataCleanupService {

    private final ApiEndpointRepository apiEndpointRepository;

    @Override
    @Transactional
    public void cleanupProjectApiMetadata(UUID projectId) {
        log.info("[ApiCleanup] Starting cleanup for projectId={}", projectId);
        List<ApiEndpoint> activeEndpoints = apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId);

        int fallbackMarkedStale = cleanupLegacyFallbackEndpoints(activeEndpoints);
        int duplicatesMarkedStale = cleanupDuplicateEndpoints(activeEndpoints);

        apiEndpointRepository.saveAll(activeEndpoints);
        log.info("[ApiCleanup] Completed for projectId={}. Fallback stale={}, Duplicates stale={}",
                projectId, fallbackMarkedStale, duplicatesMarkedStale);
    }

    private int cleanupLegacyFallbackEndpoints(List<ApiEndpoint> endpoints) {
        int count = 0;
        Map<String, List<ApiEndpoint>> byController = endpoints.stream()
                .filter(e -> e.getControllerName() != null && !e.getControllerName().isBlank())
                .collect(Collectors.groupingBy(ApiEndpoint::getControllerName));

        for (Map.Entry<String, List<ApiEndpoint>> entry : byController.entrySet()) {
            String controllerName = entry.getKey();
            List<ApiEndpoint> group = entry.getValue();

            boolean hasRealEndpoint = group.stream().anyMatch(e -> !isFallbackClassNameEndpoint(e, controllerName));

            if (hasRealEndpoint) {
                for (ApiEndpoint e : group) {
                    if (!Boolean.TRUE.equals(e.getStaleFlag()) && isFallbackClassNameEndpoint(e, controllerName)) {
                        e.setStaleFlag(true);
                        e.setActiveFlag(false);
                        count++;
                        log.debug("[ApiCleanup] Marked fallback endpoint as stale: {} {}", e.getHttpMethod(), e.getEndpointPath());
                    }
                }
            }
        }
        return count;
    }

    private int cleanupDuplicateEndpoints(List<ApiEndpoint> endpoints) {
        int count = 0;
        Map<String, List<ApiEndpoint>> byMethodAndPath = endpoints.stream()
                .filter(e -> !Boolean.TRUE.equals(e.getStaleFlag()))
                .collect(Collectors.groupingBy(e -> {
                    String method = e.getHttpMethod() != null ? e.getHttpMethod().name() : "GET";
                    return method + ":" + normalizePath(e.getEndpointPath());
                }));

        for (List<ApiEndpoint> group : byMethodAndPath.values()) {
            if (group.size() > 1) {
                group.sort((e1, e2) -> {
                    int scoreCompare = Integer.compare(calculateEndpointQualityScore(e2), calculateEndpointQualityScore(e1));
                    if (scoreCompare != 0) return scoreCompare;
                    if (e1.getCreatedAt() != null && e2.getCreatedAt() != null) {
                        return e2.getCreatedAt().compareTo(e1.getCreatedAt()); // newer first
                    }
                    return 0;
                });

                for (int i = 1; i < group.size(); i++) {
                    ApiEndpoint duplicate = group.get(i);
                    duplicate.setStaleFlag(true);
                    duplicate.setActiveFlag(false);
                    count++;
                    log.debug("[ApiCleanup] Marked duplicate endpoint as stale: {} {}", duplicate.getHttpMethod(), duplicate.getEndpointPath());
                }
            }
        }
        return count;
    }

    private boolean isFallbackClassNameEndpoint(ApiEndpoint endpoint, String controllerName) {
        String path = endpoint.getEndpointPath();
        if (path == null) return false;

        String lowerFirstController = controllerName.substring(0, 1).toLowerCase() + controllerName.substring(1);

        boolean isClassNamePath = path.equals("/" + controllerName) || path.equals("/" + lowerFirstController) ||
                                  path.equals("/" + controllerName.replaceAll("Action$", ""));

        boolean isLegacyTag = "Servlet".equals(endpoint.getTagName()) || "Struts".equals(endpoint.getTagName());

        return isClassNamePath || (isLegacyTag && path.split("/").length <= 2 && !path.contains("legacy"));
    }

    private int calculateEndpointQualityScore(ApiEndpoint endpoint) {
        int score = 0;
        if (endpoint.getControllerName() != null && !isFallbackClassNameEndpoint(endpoint, endpoint.getControllerName())) {
            score += 100;
        } else {
            score -= 100;
        }

        if (endpoint.getEndpointPath() != null && endpoint.getEndpointPath().contains("/legacy/")) {
            score += 50;
        }

        if (endpoint.getAiSummary() != null && !endpoint.getAiSummary().isBlank()) {
            score += 20;
        }
        if (endpoint.getAiDescription() != null && !endpoint.getAiDescription().isBlank()) {
            score += 20;
        }

        if (endpoint.getExampleRequestJson() != null && !endpoint.getExampleRequestJson().isBlank()) {
            score += 10;
        }
        if (endpoint.getExampleResponseJson() != null && !endpoint.getExampleResponseJson().isBlank()) {
            score += 10;
        }
        
        // checking parameters and schemas
        if (endpoint.getApiParameters() != null && !endpoint.getApiParameters().isEmpty()) {
            score += 30;
        }
        if (endpoint.getEndpointSchemaMaps() != null && !endpoint.getEndpointSchemaMaps().isEmpty()) {
            score += 30;
        }

        return score;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.trim().replace("\\", "/").replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
