package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apimetadata.res.ApiMetadataCleanupResult;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
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

    @Test
    void gatewayClassFallbackEndpointsAreStaleButBusinessRoutesStayActive() {
        ApiEndpoint getFallback = endpoint(HttpMethod.GET, "/OrderGateway", "OrderGateway", "Servlet");
        ApiEndpoint postFallback = endpoint(HttpMethod.POST, "/OrderGateway", "OrderGateway", "Servlet");
        ApiEndpoint getBusiness = endpoint(HttpMethod.GET, "/legacy/orders", "OrderGateway", null);
        ApiEndpoint postBusiness = endpoint(HttpMethod.POST, "/legacy/orders", "OrderGateway", null);
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
        ApiEndpoint getFallback = endpoint(HttpMethod.GET, "/StocktakeGateway", "StocktakeGateway", "Servlet");
        ApiEndpoint postFallback = endpoint(HttpMethod.POST, "/StocktakeGateway", "StocktakeGateway", "Servlet");
        ApiEndpoint getBusiness = endpoint(HttpMethod.GET, "/legacy/stocktakes", "StocktakeGateway", null);
        ApiEndpoint postBusiness = endpoint(HttpMethod.POST, "/legacy/stocktakes", "StocktakeGateway", null);
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
        ApiEndpoint actionBase = endpoint(HttpMethod.POST, "/Inventory", "InventoryAction", "Struts");
        ApiEndpoint getActionClass = endpoint(HttpMethod.GET, "/InventoryAction", "InventoryAction", null);
        ApiEndpoint postActionClass = endpoint(HttpMethod.POST, "/InventoryAction", "InventoryAction", null);
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
        ApiEndpoint staleFallback = endpoint(HttpMethod.GET, "/InventoryAction", "InventoryAction", null);
        staleFallback.setActiveFlag(false);
        staleFallback.setStaleFlag(true);
        when(apiEndpointRepository.findBySourceProjectId(projectId)).thenReturn(List.of(staleFallback));

        ApiMetadataCleanupResult result = cleanupService.cleanupProjectApiMetadata(projectId);

        assertStale(staleFallback);
        assertEquals(0, result.getFallbackMarkedStale());
        assertEquals(0, result.getActiveAfter());
    }

    private ApiEndpoint endpoint(HttpMethod method, String path, String controllerName, String tagName) {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setId(UUID.randomUUID());
        endpoint.setHttpMethod(method);
        endpoint.setEndpointPath(path);
        endpoint.setControllerName(controllerName);
        endpoint.setTagName(tagName);
        endpoint.setActiveFlag(true);
        endpoint.setStaleFlag(false);
        return endpoint;
    }

    private void assertStale(ApiEndpoint endpoint) {
        assertFalse(endpoint.getActiveFlag());
        assertTrue(endpoint.getStaleFlag());
    }

    private void assertActive(ApiEndpoint endpoint) {
        assertTrue(endpoint.getActiveFlag());
        assertFalse(endpoint.getStaleFlag());
    }
}
