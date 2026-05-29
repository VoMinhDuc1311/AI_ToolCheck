package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apimetadata.res.ApiMetadataCleanupResult;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceUploadVersion;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.notification.ProjectNotificationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiMetadataCleanupServiceImplTest {

    @Mock private ApiEndpointRepository apiEndpointRepository;
    @Mock private ProjectNotificationEventPublisher notificationEventPublisher;

    private ApiMetadataCleanupServiceImpl cleanupService;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        cleanupService = new ApiMetadataCleanupServiceImpl(apiEndpointRepository, notificationEventPublisher);
        projectId = UUID.randomUUID();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Existing tests (must still pass — regression)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void gatewayClassFallbackEndpointsAreStaleButBusinessRoutesStayActive() {
        ApiEndpoint getFallback = legacyEndpoint(HttpMethod.GET, "/OrderGateway", "OrderGateway", "Servlet");
        ApiEndpoint postFallback = legacyEndpoint(HttpMethod.POST, "/OrderGateway", "OrderGateway", "Servlet");
        ApiEndpoint getBusiness = legacyEndpoint(HttpMethod.GET, "/legacy/orders", "OrderGateway", null);
        ApiEndpoint postBusiness = legacyEndpoint(HttpMethod.POST, "/legacy/orders", "OrderGateway", null);
        when(apiEndpointRepository.findBySourceProjectId(projectId))
                .thenReturn(List.of(getFallback, postFallback, getBusiness, postBusiness));

        ApiMetadataCleanupResult result = cleanupService.cleanupProjectApiMetadata(projectId);

        assertStale(getFallback);
        assertStale(postFallback);
        assertActive(getBusiness);
        assertActive(postBusiness);
        assertEquals(2, result.getFallbackMarkedStale());
        assertEquals(2, result.getActiveAfter());
        assertEquals(0, result.getRawFallbackRemaining());
    }

    @Test
    void stocktakeGatewayClassFallbackEndpointsAreStaleButBusinessRoutesStayActive() {
        ApiEndpoint getFallback = legacyEndpoint(HttpMethod.GET, "/StocktakeGateway", "StocktakeGateway", "Servlet");
        ApiEndpoint postFallback = legacyEndpoint(HttpMethod.POST, "/StocktakeGateway", "StocktakeGateway", "Servlet");
        ApiEndpoint getBusiness = legacyEndpoint(HttpMethod.GET, "/legacy/stocktakes", "StocktakeGateway", null);
        ApiEndpoint postBusiness = legacyEndpoint(HttpMethod.POST, "/legacy/stocktakes", "StocktakeGateway", null);
        when(apiEndpointRepository.findBySourceProjectId(projectId))
                .thenReturn(List.of(getFallback, postFallback, getBusiness, postBusiness));

        cleanupService.cleanupProjectApiMetadata(projectId);

        assertStale(getFallback);
        assertStale(postFallback);
        assertActive(getBusiness);
        assertActive(postBusiness);
    }

    @Test
    void actionClassAndActionBaseFallbackEndpointsAreStale() {
        ApiEndpoint actionBase = legacyEndpoint(HttpMethod.POST, "/Inventory", "InventoryAction", "Struts");
        ApiEndpoint getActionClass = legacyEndpoint(HttpMethod.GET, "/InventoryAction", "InventoryAction", null);
        ApiEndpoint postActionClass = legacyEndpoint(HttpMethod.POST, "/InventoryAction", "InventoryAction", null);
        when(apiEndpointRepository.findBySourceProjectId(projectId))
                .thenReturn(List.of(actionBase, getActionClass, postActionClass));

        ApiMetadataCleanupResult result = cleanupService.cleanupProjectApiMetadata(projectId);

        assertStale(actionBase);
        assertStale(getActionClass);
        assertStale(postActionClass);
        assertEquals(3, result.getFallbackMarkedStale());
        assertEquals(0, result.getActiveAfter());
        assertEquals(0, result.getRawFallbackRemaining());
    }

    @Test
    void cleanupIsIdempotentAndDoesNotReactivateStaleEndpoints() {
        ApiEndpoint staleFallback = legacyEndpoint(HttpMethod.GET, "/InventoryAction", "InventoryAction", null);
        staleFallback.setActiveFlag(false);
        staleFallback.setStaleFlag(true);
        when(apiEndpointRepository.findBySourceProjectId(projectId)).thenReturn(List.of(staleFallback));

        ApiMetadataCleanupResult result = cleanupService.cleanupProjectApiMetadata(projectId);

        assertStale(staleFallback);
        assertEquals(0, result.getFallbackMarkedStale());
        assertEquals(0, result.getActiveAfter());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FIX 1 — Spring MVC endpoints with sourceUploadVersion must not be staled
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Regression for gs-rest-service:
     * GreetingController + GET /greeting must survive cleanup unchanged.
     * The path segment "greeting" equals the controller name minus "Controller",
     * but this is a valid Spring MVC REST endpoint with a real sourceUploadVersion
     * and sourceFile — it must NEVER be classified as fallback.
     */
    @Test
    void springMvcEndpointWithSourceUploadVersionIsNotMarkedStale() {
        ApiEndpoint greetingEndpoint = parserEndpoint(HttpMethod.GET, "/greeting", "GreetingController", "Greeting");

        when(apiEndpointRepository.findBySourceProjectId(projectId))
                .thenReturn(List.of(greetingEndpoint));

        ApiMetadataCleanupResult result = cleanupService.cleanupProjectApiMetadata(projectId);

        assertActive(greetingEndpoint);
        assertEquals(0, result.getFallbackMarkedStale());
        assertEquals(1, result.getActiveAfter());
    }

    @Test
    void springMvcMultiSegmentEndpointIsNotMarkedStale() {
        ApiEndpoint apiEndpoint = parserEndpoint(HttpMethod.GET, "/api/v1/customers", "CustomerController", "Customers");
        ApiEndpoint postEndpoint = parserEndpoint(HttpMethod.POST, "/api/v1/orders", "OrderController", "Orders");

        when(apiEndpointRepository.findBySourceProjectId(projectId))
                .thenReturn(List.of(apiEndpoint, postEndpoint));

        ApiMetadataCleanupResult result = cleanupService.cleanupProjectApiMetadata(projectId);

        assertActive(apiEndpoint);
        assertActive(postEndpoint);
        assertEquals(0, result.getFallbackMarkedStale());
        assertEquals(2, result.getActiveAfter());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FIX 1 — Duplicate dedup: parser endpoint wins over legacy-AI endpoint
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void duplicateCleanupPrefersParserEndpointOverLegacyAiEndpoint() {
        // Same logical key: GET /greeting
        ApiEndpoint parserEp = parserEndpoint(HttpMethod.GET, "/greeting", "GreetingController", "Greeting");
        // Legacy-AI endpoint: same path but no sourceUploadVersion/sourceFile
        ApiEndpoint legacyEp = legacyEndpoint(HttpMethod.GET, "/greeting", "GreetingController", null);

        when(apiEndpointRepository.findBySourceProjectId(projectId))
                .thenReturn(List.of(parserEp, legacyEp));

        ApiMetadataCleanupResult result = cleanupService.cleanupProjectApiMetadata(projectId);

        // Parser endpoint must survive; legacy endpoint is correctly identified as a
        // legacy fallback (missing sourceUploadVersion/sourceFile) and staled by the
        // fallback cleanup phase — not the duplicate phase.
        assertActive(parserEp);
        assertStale(legacyEp);
        // The legacy endpoint is caught by fallback cleanup (1), not duplicate cleanup (0)
        assertEquals(1, result.getFallbackMarkedStale());
        assertEquals(0, result.getDuplicatesMarkedStale());
        assertEquals(1, result.getActiveAfter());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FIX 3 — Notification failure must not affect cleanup outcome
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void notificationFailureDoesNotAffectCleanupResult() {
        ApiEndpoint greetingEndpoint = parserEndpoint(HttpMethod.GET, "/greeting", "GreetingController", "Greeting");
        when(apiEndpointRepository.findBySourceProjectId(projectId))
                .thenReturn(List.of(greetingEndpoint));
        // Simulate notification publisher throwing a runtime exception
        doThrow(new RuntimeException("WebSocket broker unavailable"))
                .when(notificationEventPublisher)
                .publishForCurrentUser(any(), any(), any(), any(), any(), any(), any());

        // Must not throw — cleanup result must be correct despite notification failure
        ApiMetadataCleanupResult result = cleanupService.cleanupProjectApiMetadata(projectId);

        assertActive(greetingEndpoint);
        assertEquals(0, result.getFallbackMarkedStale());
        assertEquals(1, result.getActiveAfter());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates an endpoint that looks like it was produced by the JavaParser pipeline:
     * has both sourceFile and sourceUploadVersion set.
     */
    private ApiEndpoint parserEndpoint(HttpMethod method, String path, String controllerName, String tagName) {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setId(UUID.randomUUID());
        endpoint.setHttpMethod(method);
        endpoint.setEndpointPath(path);
        endpoint.setControllerName(controllerName);
        endpoint.setTagName(tagName);
        endpoint.setActiveFlag(true);
        endpoint.setStaleFlag(false);
        // Simulate parser-produced: both sourceFile and sourceUploadVersion are set
        SourceFile sourceFile = new SourceFile();
        sourceFile.setId(UUID.randomUUID());
        endpoint.setSourceFile(sourceFile);
        SourceUploadVersion version = new SourceUploadVersion();
        version.setId(UUID.randomUUID());
        endpoint.setSourceUploadVersion(version);
        return endpoint;
    }

    /**
     * Creates an endpoint without sourceFile/sourceUploadVersion — simulates a legacy-AI-
     * produced endpoint or a bare test fixture (the pre-existing test pattern).
     */
    private ApiEndpoint legacyEndpoint(HttpMethod method, String path, String controllerName, String tagName) {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setId(UUID.randomUUID());
        endpoint.setHttpMethod(method);
        endpoint.setEndpointPath(path);
        endpoint.setControllerName(controllerName);
        endpoint.setTagName(tagName);
        endpoint.setActiveFlag(true);
        endpoint.setStaleFlag(false);
        // No sourceFile / sourceUploadVersion → legacy indicator
        return endpoint;
    }

    private void assertStale(ApiEndpoint endpoint) {
        assertFalse(endpoint.getActiveFlag(), "Expected endpoint to be stale (activeFlag=false): " + endpoint.getEndpointPath());
        assertTrue(endpoint.getStaleFlag(), "Expected endpoint to be stale (staleFlag=true): " + endpoint.getEndpointPath());
    }

    private void assertActive(ApiEndpoint endpoint) {
        assertTrue(endpoint.getActiveFlag(), "Expected endpoint to be active (activeFlag=true): " + endpoint.getEndpointPath());
        assertFalse(endpoint.getStaleFlag(), "Expected endpoint to be active (staleFlag=false): " + endpoint.getEndpointPath());
    }
}
