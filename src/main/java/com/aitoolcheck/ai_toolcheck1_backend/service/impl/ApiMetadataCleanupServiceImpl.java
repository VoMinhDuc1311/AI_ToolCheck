package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apimetadata.res.ApiMetadataCleanupResult;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiMetadataCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
    public ApiMetadataCleanupResult cleanupProjectApiMetadata(UUID projectId) {
        log.info("[ApiCleanup] Starting cleanup for projectId={}", projectId);
        List<ApiEndpoint> allEndpoints = apiEndpointRepository.findBySourceProjectId(projectId);
        
        int activeBefore = (int) allEndpoints.stream()
                .filter(e -> Boolean.TRUE.equals(e.getActiveFlag()) && !Boolean.TRUE.equals(e.getStaleFlag()))
                .count();

        int fallbackMarkedStale = cleanupLegacyFallbackEndpoints(allEndpoints);
        int duplicatesMarkedStale = cleanupDuplicateEndpoints(allEndpoints);

        apiEndpointRepository.saveAll(allEndpoints);
        apiEndpointRepository.flush();
        
        int activeAfter = (int) allEndpoints.stream()
                .filter(e -> Boolean.TRUE.equals(e.getActiveFlag()) && !Boolean.TRUE.equals(e.getStaleFlag()))
                .count();
        int rawFallbackRemaining = (int) allEndpoints.stream()
                .filter(e -> Boolean.TRUE.equals(e.getActiveFlag()) && !Boolean.TRUE.equals(e.getStaleFlag()))
                .filter(e -> isFallbackClassNameEndpoint(e, e.getControllerName()))
                .count();
        List<String> cleanupWarnings = new ArrayList<>();
        if (rawFallbackRemaining > 0) {
            cleanupWarnings.add("Raw fallback endpoints remain active after cleanup: " + rawFallbackRemaining);
        }

        log.info("[ApiCleanup] Completed for projectId={}. Before={}, Fallback stale={}, Duplicates stale={}, After={}",
                projectId, activeBefore, fallbackMarkedStale, duplicatesMarkedStale, activeAfter);
                
        ApiMetadataCleanupResult result = ApiMetadataCleanupResult.builder()
                .projectId(projectId)
                .activeBefore(activeBefore)
                .fallbackMarkedStale(fallbackMarkedStale)
                .duplicatesMarkedStale(duplicatesMarkedStale)
                .activeAfter(activeAfter)
                .rawFallbackRemaining(rawFallbackRemaining)
                .activeCleanEndpoints(activeAfter)
                .cleanupWarnings(cleanupWarnings)
                .build();

        return result;
    }

    private int cleanupLegacyFallbackEndpoints(List<ApiEndpoint> endpoints) {
        int count = 0;
        Map<String, List<ApiEndpoint>> byController = endpoints.stream()
                .filter(e -> e.getControllerName() != null && !e.getControllerName().isBlank())
                .collect(Collectors.groupingBy(ApiEndpoint::getControllerName));

        for (Map.Entry<String, List<ApiEndpoint>> entry : byController.entrySet()) {
            String controllerName = entry.getKey();
            List<ApiEndpoint> group = entry.getValue();

            for (ApiEndpoint e : group) {
                if (Boolean.TRUE.equals(e.getActiveFlag())
                        && !Boolean.TRUE.equals(e.getStaleFlag())
                        && isFallbackClassNameEndpoint(e, controllerName)) {
                    markStale(e);
                    count++;
                    log.debug("[ApiCleanup] Marked fallback endpoint as stale: {} {}", e.getHttpMethod(), e.getEndpointPath());
                }
            }
        }
        return count;
    }

    private int cleanupDuplicateEndpoints(List<ApiEndpoint> endpoints) {
        int count = 0;
        Map<String, List<ApiEndpoint>> byLogicalKey = endpoints.stream()
                .filter(e -> Boolean.TRUE.equals(e.getActiveFlag()) && !Boolean.TRUE.equals(e.getStaleFlag()))
                .collect(Collectors.groupingBy(this::logicalKey));

        for (List<ApiEndpoint> group : byLogicalKey.values()) {
            if (group.size() > 1) {
                group.sort((e1, e2) -> {
                    int scoreCompare = Integer.compare(calculateEndpointQualityScore(e2), calculateEndpointQualityScore(e1));
                    if (scoreCompare != 0) return scoreCompare;
                    
                    int descCompare = Integer.compare(
                        e2.getDescription() != null ? e2.getDescription().length() : 0,
                        e1.getDescription() != null ? e1.getDescription().length() : 0
                    );
                    if (descCompare != 0) return descCompare;
                    
                    if (e1.getCreatedAt() != null && e2.getCreatedAt() != null) {
                        return e2.getCreatedAt().compareTo(e1.getCreatedAt()); // newer first
                    }
                    return e1.getId().toString().compareTo(e2.getId().toString());
                });

                for (int i = 1; i < group.size(); i++) {
                    ApiEndpoint duplicate = group.get(i);
                    markStale(duplicate);
                    count++;
                    log.debug("[ApiCleanup] Marked duplicate endpoint as stale: {} {}", duplicate.getHttpMethod(), duplicate.getEndpointPath());
                }
            }
        }
        return count;
    }
    
    private String logicalKey(ApiEndpoint endpoint) {
        String method = endpoint.getHttpMethod() != null ? endpoint.getHttpMethod().name().toUpperCase() : "GET";
        String path = normalizePath(endpoint.getEndpointPath()).toLowerCase();
        return method + " " + path;
    }
    
    private void markStale(ApiEndpoint endpoint) {
        endpoint.setActiveFlag(false);
        endpoint.setStaleFlag(true);
        endpoint.setUpdatedAt(LocalDateTime.now());
    }

    private boolean isFallbackClassNameEndpoint(ApiEndpoint endpoint, String controllerName) {
        String normalizedPath = normalizePath(endpoint.getEndpointPath());
        if (normalizedPath == null || "/".equals(normalizedPath)) return false;

        String lastSegment = lastPathSegment(normalizedPath);
        if (lastSegment == null || lastSegment.isBlank()) return false;

        String controller = controllerName == null ? "" : controllerName.trim();
        String controllerLower = controller.toLowerCase();
        String segmentLower = lastSegment.toLowerCase();
        boolean singleSegment = normalizedPath.indexOf('/', 1) < 0;

        if (singleSegment && !controller.isBlank()) {
            if (segmentLower.equals(controllerLower)) {
                return true;
            }
            if (controllerLower.endsWith("action")
                    && segmentLower.equals(stripSuffix(controller, "Action").toLowerCase())) {
                return true;
            }
            if (controllerLower.endsWith("gateway")
                    && segmentLower.equals(stripSuffix(controller, "Gateway").toLowerCase())) {
                return true;
            }
            if (controllerLower.endsWith("servlet")
                    && segmentLower.equals(stripSuffix(controller, "Servlet").toLowerCase())) {
                return true;
            }
            if (controllerLower.endsWith("controller")
                    && segmentLower.equals(stripSuffix(controller, "Controller").toLowerCase())) {
                return true;
            }
        }

        boolean suffixLooksLikeJavaClass = segmentLower.endsWith("action")
                || segmentLower.endsWith("gateway")
                || segmentLower.endsWith("servlet")
                || segmentLower.endsWith("controller");
        boolean controllerMatchesClassSignal = !controller.isBlank()
                && (controllerLower.equals(segmentLower)
                    || controllerLower.startsWith(segmentLower)
                    || segmentLower.startsWith(controllerLower));

        boolean isLegacyTag = "Servlet".equals(endpoint.getTagName()) || "Struts".equals(endpoint.getTagName());
        return singleSegment && (suffixLooksLikeJavaClass && (controllerMatchesClassSignal || isLegacyTag)
                || isLegacyTag);
    }

    private int calculateEndpointQualityScore(ApiEndpoint endpoint) {
        int score = 0;
        if (endpoint.getEndpointPath() != null && endpoint.getEndpointPath().contains("/legacy/")) {
            score += 100;
        }
        
        if (endpoint.getControllerName() != null && !isFallbackClassNameEndpoint(endpoint, endpoint.getControllerName())) {
            score += 50;
        } else if (endpoint.getControllerName() == null) {
            score += 50;
        }
        
        if (isFallbackClassNameEndpoint(endpoint, endpoint.getControllerName() != null ? endpoint.getControllerName() : "")) {
            score -= 200;
        }
        
        String path = endpoint.getEndpointPath();
        boolean isLegacyTag = "Servlet".equals(endpoint.getTagName()) || "Struts".equals(endpoint.getTagName());
        if (isLegacyTag && path != null && normalizePath(path).split("/").length <= 2) {
            score -= 100;
        }

        if (endpoint.getDescription() != null && !endpoint.getDescription().isBlank()) {
            score += 30;
        }
        
        if ((endpoint.getAiSummary() != null && !endpoint.getAiSummary().isBlank()) || 
            (endpoint.getAiDescription() != null && !endpoint.getAiDescription().isBlank())) {
            score += 20;
        }
        
        if (endpoint.getStableKey() != null && !endpoint.getStableKey().isBlank()) {
            score += 10;
        }
        
        if (endpoint.getOperationId() != null && !endpoint.getOperationId().isBlank()) {
            score += 10;
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

    private String lastPathSegment(String path) {
        String normalized = normalizePath(path);
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
    }

    private String stripSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }
}
