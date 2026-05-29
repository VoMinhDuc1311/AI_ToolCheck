package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apimetadata.res.ApiMetadataCleanupResult;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationSeverity;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationType;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiMetadataCleanupService;
import com.aitoolcheck.ai_toolcheck1_backend.service.notification.ProjectNotificationEventPublisher;
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
    private final ProjectNotificationEventPublisher notificationEventPublisher;

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
                .filter(this::isConfirmedLegacyFallback)
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

        // FIX 3: Notification is a side effect — wrap in try/catch so that any publisher
        // failure cannot mark the business transaction rollback-only.
        try {
            notificationEventPublisher.publishForCurrentUser(
                    projectId,
                    NotificationType.METADATA_CLEANUP_COMPLETED,
                    rawFallbackRemaining > 0 ? NotificationSeverity.WARNING : NotificationSeverity.SUCCESS,
                    "API metadata cleanup completed",
                    "API metadata cleanup completed for the project.",
                    "/source-projects/" + projectId + "/documentation",
                    Map.of(
                            "projectId", projectId,
                            "cleanedEndpointCount", activeAfter,
                            "staleEndpointCount", fallbackMarkedStale + duplicatesMarkedStale,
                            "status", rawFallbackRemaining > 0 ? "COMPLETED_WITH_WARNINGS" : "COMPLETED"
                    ));
        } catch (Exception notifEx) {
            log.warn("[ApiCleanup] Notification publish failed for projectId={} — cleanup result is still valid. Reason: {}",
                    projectId, notifEx.getMessage());
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Legacy fallback cleanup
    // ─────────────────────────────────────────────────────────────────────────

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

    /**
     * Determines whether an endpoint is a legacy/fallback artifact that should be cleaned up.
     *
     * <p><strong>Key design rule (FIX 1):</strong> A standard Spring MVC endpoint parsed by
     * JavaParser always has both {@code sourceUploadVersion} and {@code sourceFile} set.
     * Such endpoints must NEVER be classified as fallback merely because the last path segment
     * happens to equal the controller name with the "Controller" suffix stripped — that is a
     * perfectly valid REST URL convention.
     *
     * <p>An endpoint is only eligible for fallback classification when it has at least one
     * explicit legacy indicator:
     * <ul>
     *   <li>A legacy tag name ({@code Servlet}, {@code Struts}, {@code Legacy Router}, or
     *       {@code JAX-RS})</li>
     *   <li>Missing {@code sourceUploadVersion} — parser-produced endpoints always carry the
     *       upload version; absence is a reliable signal of legacy AI generation.</li>
     *   <li>Missing {@code sourceFile} — same reasoning.</li>
     * </ul>
     */
    private boolean isFallbackClassNameEndpoint(ApiEndpoint endpoint, String controllerName) {
        // FIX 1: Require at least one explicit legacy indicator.  Endpoints from the
        // JavaParser pipeline carry both sourceUploadVersion and sourceFile and must
        // never be classified as fallback via the path-segment heuristic alone.
        if (!hasLegacyIndicator(endpoint)) {
            return false;
        }

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

        boolean isLegacyTag = isLegacyTagName(endpoint.getTagName());
        return singleSegment && (suffixLooksLikeJavaClass && (controllerMatchesClassSignal || isLegacyTag)
                || isLegacyTag);
    }

    /**
     * Returns {@code true} when at least one reliable legacy indicator is present on this
     * endpoint.  Endpoints created by the JavaParser pipeline always have both
     * {@code sourceUploadVersion} and {@code sourceFile} set; absence of either field is a
     * strong signal that the row was produced by the legacy AI inference path.
     */
    private boolean hasLegacyIndicator(ApiEndpoint endpoint) {
        if (isLegacyTagName(endpoint.getTagName())) {
            return true;
        }
        // Parser-produced endpoints always have both fields; missing either → legacy AI origin.
        if (endpoint.getSourceFile() == null || endpoint.getSourceUploadVersion() == null) {
            return true;
        }
        return false;
    }

    private boolean isLegacyTagName(String tagName) {
        return "Servlet".equals(tagName)
                || "Struts".equals(tagName)
                || "Legacy Router".equals(tagName)
                || "JAX-RS".equals(tagName);
    }

    /**
     * Convenience predicate used only for counting remaining confirmed fallbacks after cleanup.
     */
    private boolean isConfirmedLegacyFallback(ApiEndpoint endpoint) {
        if (endpoint.getControllerName() == null) return false;
        return isFallbackClassNameEndpoint(endpoint, endpoint.getControllerName());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Duplicate cleanup
    // ─────────────────────────────────────────────────────────────────────────

    private int cleanupDuplicateEndpoints(List<ApiEndpoint> endpoints) {
        int count = 0;
        Map<String, List<ApiEndpoint>> byLogicalKey = endpoints.stream()
                .filter(e -> Boolean.TRUE.equals(e.getActiveFlag()) && !Boolean.TRUE.equals(e.getStaleFlag()))
                .collect(Collectors.groupingBy(this::logicalKey));

        for (List<ApiEndpoint> group : byLogicalKey.values()) {
            if (group.size() > 1) {
                // FIX 1 (duplicate priority): prefer endpoints with a sourceUploadVersion
                // (parser-produced) and sourceFile over those without (legacy-AI-produced).
                group.sort((e1, e2) -> {
                    // 1. Endpoints with sourceUploadVersion beat those without
                    boolean v1 = e1.getSourceUploadVersion() != null;
                    boolean v2 = e2.getSourceUploadVersion() != null;
                    if (v1 != v2) return v1 ? -1 : 1;

                    // 2. Endpoints with sourceFile beat those without
                    boolean f1 = e1.getSourceFile() != null;
                    boolean f2 = e2.getSourceFile() != null;
                    if (f1 != f2) return f1 ? -1 : 1;

                    // 3. Higher quality score wins
                    int scoreCompare = Integer.compare(calculateEndpointQualityScore(e2), calculateEndpointQualityScore(e1));
                    if (scoreCompare != 0) return scoreCompare;

                    // 4. Longer description preferred
                    int descCompare = Integer.compare(
                        e2.getDescription() != null ? e2.getDescription().length() : 0,
                        e1.getDescription() != null ? e1.getDescription().length() : 0
                    );
                    if (descCompare != 0) return descCompare;

                    // 5. Newer createdAt preferred
                    if (e1.getCreatedAt() != null && e2.getCreatedAt() != null) {
                        return e2.getCreatedAt().compareTo(e1.getCreatedAt());
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

    // ─────────────────────────────────────────────────────────────────────────
    // Quality scoring (used for duplicate deduplication)
    // ─────────────────────────────────────────────────────────────────────────

    private int calculateEndpointQualityScore(ApiEndpoint endpoint) {
        int score = 0;
        if (endpoint.getEndpointPath() != null && endpoint.getEndpointPath().contains("/legacy/")) {
            score += 100;
        }

        if (endpoint.getControllerName() != null && !isConfirmedLegacyFallback(endpoint)) {
            score += 50;
        } else if (endpoint.getControllerName() == null) {
            score += 50;
        }

        if (isConfirmedLegacyFallback(endpoint)) {
            score -= 200;
        }

        String path = endpoint.getEndpointPath();
        boolean isLegacyTag = isLegacyTagName(endpoint.getTagName());
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

    // ─────────────────────────────────────────────────────────────────────────
    // Path utilities
    // ─────────────────────────────────────────────────────────────────────────

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
