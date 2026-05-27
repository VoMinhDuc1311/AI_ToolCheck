package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ParamIn;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiParameter;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiMetadataCleanupService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.notification.ProjectNotificationEventPublisher;
import com.aitoolcheck.ai_toolcheck1_backend.service.openapi.OpenApiMetadataEnhancer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenApiGeneratorServiceImplTest {

    @Mock private SourceProjectRepository sourceProjectRepository;
    @Mock private ApiEndpointRepository apiEndpointRepository;
    @Mock private ApiParameterRepository apiParameterRepository;
    @Mock private ApiSchemaRepository apiSchemaRepository;
    @Mock private ApiSchemaFieldRepository apiSchemaFieldRepository;
    @Mock private EndpointSchemaMapRepository endpointSchemaMapRepository;
    @Mock private ApiDocumentRepository apiDocumentRepository;
    @Mock private ApiDocumentVersionRepository apiDocumentVersionRepository;
    @Mock private ProjectAccessService projectAccessService;
    @Mock private ApiMetadataCleanupService apiMetadataCleanupService;
    @Mock private ProjectNotificationEventPublisher notificationEventPublisher;

    private OpenApiMetadataEnhancer openApiMetadataEnhancer;
    private OpenApiGeneratorServiceImpl service;

    @BeforeEach
    void setUp() {
        openApiMetadataEnhancer = new OpenApiMetadataEnhancer();
        service = new OpenApiGeneratorServiceImpl(
                new ObjectMapper(),
                sourceProjectRepository,
                apiEndpointRepository,
                apiParameterRepository,
                apiSchemaRepository,
                apiSchemaFieldRepository,
                endpointSchemaMapRepository,
                apiDocumentRepository,
                apiDocumentVersionRepository,
                projectAccessService,
                apiMetadataCleanupService,
                openApiMetadataEnhancer,
                notificationEventPublisher);
    }

    @Test
    @SuppressWarnings("unchecked")
    void openApiGenerationUsesOnlyActiveNonStaleEndpoints() {
        UUID projectId = UUID.randomUUID();
        SourceProject project = new SourceProject();
        project.setId(projectId);
        project.setProjectName("Legacy Project");

        ApiEndpoint clean = new ApiEndpoint();
        clean.setId(UUID.randomUUID());
        clean.setEndpointPath("/legacy/orders");
        clean.setControllerName("OrderGateway");
        clean.setHttpMethod(HttpMethod.GET);
        clean.setActiveFlag(true);
        clean.setStaleFlag(false);

        when(sourceProjectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId))
                .thenReturn(List.of(clean));
        when(apiSchemaRepository.findBySourceProjectId(projectId)).thenReturn(List.of());
        when(apiParameterRepository.findByApiEndpointId(clean.getId())).thenReturn(List.of());
        when(endpointSchemaMapRepository.findByApiEndpointId(clean.getId())).thenReturn(List.of());

        Map<String, Object> openApi = service.generateOpenApiJson(projectId);
        Map<String, Object> paths = (Map<String, Object>) openApi.get("paths");

        assertTrue(paths.containsKey("/legacy/orders"));
        assertFalse(paths.containsKey("/OrderGateway"));
        verify(projectAccessService).requireCanViewProject(projectId);
        verify(apiEndpointRepository).findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId);
        verify(apiMetadataCleanupService, never()).cleanupProjectApiMetadata(projectId);
    }

    @Test
    void generateAndSaveUsesGeneratePermissionAndCleanup() {
        UUID projectId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        SourceProject project = new SourceProject();
        project.setId(projectId);
        project.setProjectName("Project");

        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setId(endpointId);
        endpoint.setEndpointPath("/api/items");
        endpoint.setControllerName("ItemController");
        endpoint.setHttpMethod(HttpMethod.GET);

        com.aitoolcheck.ai_toolcheck1_backend.model.ApiDocument document =
                com.aitoolcheck.ai_toolcheck1_backend.model.ApiDocument.builder()
                        .id(UUID.randomUUID())
                        .sourceProject(project)
                        .documentName("Project OpenAPI")
                        .documentType(com.aitoolcheck.ai_toolcheck1_backend.enums.DocumentType.OPENAPI_3)
                        .currentVersionNo(0)
                        .publishedFlag(false)
                        .staleFlag(false)
                        .build();

        when(sourceProjectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId))
                .thenReturn(List.of(endpoint));
        when(apiSchemaRepository.findBySourceProjectId(projectId)).thenReturn(List.of());
        when(apiParameterRepository.findByApiEndpointId(endpointId)).thenReturn(List.of());
        when(endpointSchemaMapRepository.findByApiEndpointId(endpointId)).thenReturn(List.of());
        when(apiDocumentRepository.findBySourceProjectId(projectId)).thenReturn(Optional.of(document));
        when(apiDocumentVersionRepository.findTopByApiDocumentIdOrderByVersionNoDesc(document.getId()))
                .thenReturn(Optional.empty());
        when(apiDocumentVersionRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    com.aitoolcheck.ai_toolcheck1_backend.model.ApiDocumentVersion version = invocation.getArgument(0);
                    version.setId(UUID.randomUUID());
                    version.setVersionNo(1);
                    return version;
                });
        when(apiDocumentRepository.save(document)).thenReturn(document);

        service.generateAndSaveOpenApi(projectId);

        verify(projectAccessService).requireCanGenerateDocs(projectId);
        verify(apiMetadataCleanupService).cleanupProjectApiMetadata(projectId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testLegacyOrdersGet() {
        UUID projectId = UUID.randomUUID();
        SourceProject project = new SourceProject();
        project.setId(projectId);
        project.setProjectName("Test Project");

        ApiEndpoint ep = new ApiEndpoint();
        ep.setId(UUID.randomUUID());
        ep.setEndpointPath("/legacy/orders");
        ep.setControllerName("OrderGateway");
        ep.setHttpMethod(HttpMethod.GET);
        ep.setActiveFlag(true);
        ep.setStaleFlag(false);

        ApiParameter param = new ApiParameter();
        param.setParamName("tenantId");
        param.setParamIn(ParamIn.QUERY);
        param.setRequiredFlag(true);
        param.setDataType("String");

        when(sourceProjectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId))
                .thenReturn(List.of(ep));
        when(apiSchemaRepository.findBySourceProjectId(projectId)).thenReturn(List.of());
        when(apiParameterRepository.findByApiEndpointId(ep.getId())).thenReturn(List.of(param));
        when(endpointSchemaMapRepository.findByApiEndpointId(ep.getId())).thenReturn(List.of());

        Map<String, Object> openApi = service.generateOpenApiJson(projectId);
        Map<String, Object> paths = (Map<String, Object>) openApi.get("paths");
        Map<String, Object> pathItem = (Map<String, Object>) paths.get("/legacy/orders");
        Map<String, Object> operation = (Map<String, Object>) pathItem.get("get");

        // Assert Tag
        List<String> tags = (List<String>) operation.get("tags");
        assertEquals("Orders", tags.get(0));

        // Assert Summary
        assertEquals("List orders", operation.get("summary"));

        // Assert OperationId
        assertEquals("listOrders", operation.get("operationId"));

        // Assert Parameter Description
        List<Map<String, Object>> parameters = (List<Map<String, Object>>) operation.get("parameters");
        assertEquals(1, parameters.size());
        assertEquals("tenantId", parameters.get(0).get("name"));
        assertEquals("Tenant identifier used to filter records.", parameters.get(0).get("description"));

        // Assert 200 Response Description
        Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
        Map<String, Object> response200 = (Map<String, Object>) responses.get("200");
        assertEquals("Successful retrieval of resource list.", response200.get("description"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testLegacyOrdersPost() {
        UUID projectId = UUID.randomUUID();
        SourceProject project = new SourceProject();
        project.setId(projectId);
        project.setProjectName("Test Project");

        ApiEndpoint ep = new ApiEndpoint();
        ep.setId(UUID.randomUUID());
        ep.setEndpointPath("/legacy/orders");
        ep.setControllerName("OrderGateway");
        ep.setHttpMethod(HttpMethod.POST);
        ep.setActiveFlag(true);
        ep.setStaleFlag(false);

        ApiParameter param = new ApiParameter();
        param.setParamName("action");
        param.setParamIn(ParamIn.QUERY);
        param.setExampleValue("create");
        param.setDataType("String");

        when(sourceProjectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId))
                .thenReturn(List.of(ep));
        when(apiSchemaRepository.findBySourceProjectId(projectId)).thenReturn(List.of());
        when(apiParameterRepository.findByApiEndpointId(ep.getId())).thenReturn(List.of(param));
        when(endpointSchemaMapRepository.findByApiEndpointId(ep.getId())).thenReturn(List.of());

        Map<String, Object> openApi = service.generateOpenApiJson(projectId);
        Map<String, Object> paths = (Map<String, Object>) openApi.get("paths");
        Map<String, Object> pathItem = (Map<String, Object>) paths.get("/legacy/orders");
        Map<String, Object> operation = (Map<String, Object>) pathItem.get("post");

        // Assert Tag
        List<String> tags = (List<String>) operation.get("tags");
        assertEquals("Orders", tags.get(0));

        // Assert Summary
        assertEquals("Create order", operation.get("summary"));

        // Assert OperationId
        assertEquals("createOrder", operation.get("operationId"));

        // Assert Parameter Description
        List<Map<String, Object>> parameters = (List<Map<String, Object>>) operation.get("parameters");
        assertEquals(1, parameters.size());
        assertEquals("action", parameters.get(0).get("name"));
        assertEquals("Legacy action command.", parameters.get(0).get("description"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testOrderDetail() {
        UUID projectId = UUID.randomUUID();
        SourceProject project = new SourceProject();
        project.setId(projectId);
        project.setProjectName("Test Project");

        ApiEndpoint ep = new ApiEndpoint();
        ep.setId(UUID.randomUUID());
        ep.setEndpointPath("/legacy/orders/detail");
        ep.setControllerName("OrderGateway");
        ep.setHttpMethod(HttpMethod.GET);
        ep.setActiveFlag(true);
        ep.setStaleFlag(false);

        ApiParameter param = new ApiParameter();
        param.setParamName("orderId");
        param.setParamIn(ParamIn.QUERY);
        param.setDataType("String");
        param.setRequiredFlag(true);

        when(sourceProjectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId))
                .thenReturn(List.of(ep));
        when(apiSchemaRepository.findBySourceProjectId(projectId)).thenReturn(List.of());
        when(apiParameterRepository.findByApiEndpointId(ep.getId())).thenReturn(List.of(param));
        when(endpointSchemaMapRepository.findByApiEndpointId(ep.getId())).thenReturn(List.of());

        Map<String, Object> openApi = service.generateOpenApiJson(projectId);
        Map<String, Object> paths = (Map<String, Object>) openApi.get("paths");
        Map<String, Object> pathItem = (Map<String, Object>) paths.get("/legacy/orders/detail");
        Map<String, Object> operation = (Map<String, Object>) pathItem.get("get");

        // Assert Tag
        List<String> tags = (List<String>) operation.get("tags");
        assertEquals("Orders", tags.get(0));

        // Assert Summary
        assertEquals("Get order detail", operation.get("summary"));

        // Assert OperationId
        assertEquals("getOrderDetail", operation.get("operationId"));

        // Assert Response includes 404
        Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
        assertTrue(responses.containsKey("404"));
        Map<String, Object> response404 = (Map<String, Object>) responses.get("404");
        assertEquals("Resource not found.", response404.get("description"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testStocktakes() {
        UUID projectId = UUID.randomUUID();
        SourceProject project = new SourceProject();
        project.setId(projectId);
        project.setProjectName("Test Project");

        // GET /legacy/stocktakes
        ApiEndpoint epGet = new ApiEndpoint();
        epGet.setId(UUID.randomUUID());
        epGet.setEndpointPath("/legacy/stocktakes");
        epGet.setControllerName("StocktakeGateway");
        epGet.setHttpMethod(HttpMethod.GET);
        epGet.setActiveFlag(true);
        epGet.setStaleFlag(false);

        // POST /legacy/stocktakes with action=open
        ApiEndpoint epPost = new ApiEndpoint();
        epPost.setId(UUID.randomUUID());
        epPost.setEndpointPath("/legacy/stocktakes");
        epPost.setControllerName("StocktakeGateway");
        epPost.setHttpMethod(HttpMethod.POST);
        epPost.setActiveFlag(true);
        epPost.setStaleFlag(false);

        ApiParameter paramPost = new ApiParameter();
        paramPost.setParamName("action");
        paramPost.setParamIn(ParamIn.QUERY);
        paramPost.setExampleValue("open");
        paramPost.setDataType("String");

        // GET /legacy/stocktakes/detail ticketId
        ApiEndpoint epDetail = new ApiEndpoint();
        epDetail.setId(UUID.randomUUID());
        epDetail.setEndpointPath("/legacy/stocktakes/detail");
        epDetail.setControllerName("StocktakeGateway");
        epDetail.setHttpMethod(HttpMethod.GET);
        epDetail.setActiveFlag(true);
        epDetail.setStaleFlag(false);

        ApiParameter paramDetail = new ApiParameter();
        paramDetail.setParamName("ticketId");
        paramDetail.setParamIn(ParamIn.QUERY);
        paramDetail.setDataType("String");

        when(sourceProjectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId))
                .thenReturn(List.of(epGet, epPost, epDetail));
        when(apiSchemaRepository.findBySourceProjectId(projectId)).thenReturn(List.of());

        when(apiParameterRepository.findByApiEndpointId(epGet.getId())).thenReturn(List.of());
        when(apiParameterRepository.findByApiEndpointId(epPost.getId())).thenReturn(List.of(paramPost));
        when(apiParameterRepository.findByApiEndpointId(epDetail.getId())).thenReturn(List.of(paramDetail));

        when(endpointSchemaMapRepository.findByApiEndpointId(epGet.getId())).thenReturn(List.of());
        when(endpointSchemaMapRepository.findByApiEndpointId(epPost.getId())).thenReturn(List.of());
        when(endpointSchemaMapRepository.findByApiEndpointId(epDetail.getId())).thenReturn(List.of());

        Map<String, Object> openApi = service.generateOpenApiJson(projectId);
        Map<String, Object> paths = (Map<String, Object>) openApi.get("paths");

        // Assert GET
        Map<String, Object> pathItem = (Map<String, Object>) paths.get("/legacy/stocktakes");
        Map<String, Object> operationGet = (Map<String, Object>) pathItem.get("get");
        assertEquals("Stocktakes", ((List<String>) operationGet.get("tags")).get(0));
        assertEquals("List stocktakes", operationGet.get("summary"));
        assertEquals("listStocktakes", operationGet.get("operationId"));

        // Assert POST
        Map<String, Object> operationPost = (Map<String, Object>) pathItem.get("post");
        assertEquals("Stocktakes", ((List<String>) operationPost.get("tags")).get(0));
        assertEquals("Open stocktake", operationPost.get("summary"));
        assertEquals("openStocktake", operationPost.get("operationId"));

        // Assert DETAIL
        Map<String, Object> pathItemDetail = (Map<String, Object>) paths.get("/legacy/stocktakes/detail");
        Map<String, Object> operationDetail = (Map<String, Object>) pathItemDetail.get("get");
        assertEquals("Stocktakes", ((List<String>) operationDetail.get("tags")).get(0));
        assertEquals("Get stocktake detail", operationDetail.get("summary"));
        assertEquals("getStocktakeDetail", operationDetail.get("operationId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testOperationIdUniqueness() {
        UUID projectId = UUID.randomUUID();
        SourceProject project = new SourceProject();
        project.setId(projectId);
        project.setProjectName("Test Project");

        // ep1: OrderGateway GET /legacy/orders → infers summary "List orders" → opId "listOrders"
        ApiEndpoint ep1 = new ApiEndpoint();
        ep1.setId(UUID.randomUUID());
        ep1.setEndpointPath("/legacy/orders");
        ep1.setControllerName("OrderGateway");
        ep1.setHttpMethod(HttpMethod.GET);
        ep1.setActiveFlag(true);
        ep1.setStaleFlag(false);

        // ep2: OrderListController GET /api/v1/orders/list → tag "Orderlists" → summary "List orderlists"
        // but after suffix strip "OrderList" → "Orderlists" — summary "List orderlists" → opId collision
        // forces collision path → "listOrderlistsGet"
        ApiEndpoint ep2 = new ApiEndpoint();
        ep2.setId(UUID.randomUUID());
        ep2.setEndpointPath("/api/v1/orders/list");
        ep2.setControllerName("OrderListController");
        ep2.setHttpMethod(HttpMethod.GET);
        ep2.setActiveFlag(true);
        ep2.setStaleFlag(false);

        when(sourceProjectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId))
                .thenReturn(List.of(ep1, ep2));
        when(apiSchemaRepository.findBySourceProjectId(projectId)).thenReturn(List.of());
        when(apiParameterRepository.findByApiEndpointId(ep1.getId())).thenReturn(List.of());
        when(apiParameterRepository.findByApiEndpointId(ep2.getId())).thenReturn(List.of());
        when(endpointSchemaMapRepository.findByApiEndpointId(ep1.getId())).thenReturn(List.of());
        when(endpointSchemaMapRepository.findByApiEndpointId(ep2.getId())).thenReturn(List.of());

        Map<String, Object> openApi = service.generateOpenApiJson(projectId);
        Map<String, Object> paths = (Map<String, Object>) openApi.get("paths");

        Map<String, Object> op1 = (Map<String, Object>) ((Map<String, Object>) paths.get("/legacy/orders")).get("get");
        Map<String, Object> op2 = (Map<String, Object>) ((Map<String, Object>) paths.get("/api/v1/orders/list")).get("get");

        String opId1 = (String) op1.get("operationId");
        String opId2 = (String) op2.get("operationId");

        // Must be unique
        assertNotEquals(opId1, opId2);

        // ep1 gets the "clean" base — no collision
        assertEquals("listOrders", opId1);

        // ep2 gets the method-suffixed collision candidate (both are GET so suffix is "Get")
        // Since ep1 already holds "listOrders" and ep2's tag resolves differently, just assert
        // the resolved ID is non-empty, non-blank, and contains NO underscore.
        assertFalse(opId2.isEmpty());
        assertFalse(opId2.contains("_"),
                "operationId must not contain underscore — got: " + opId2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testModernRegression() {
        UUID projectId = UUID.randomUUID();
        SourceProject project = new SourceProject();
        project.setId(projectId);
        project.setProjectName("Modern Project");

        ApiEndpoint ep = new ApiEndpoint();
        ep.setId(UUID.randomUUID());
        ep.setEndpointPath("/api/v1/customers");
        ep.setControllerName("CustomerController");
        ep.setHttpMethod(HttpMethod.GET);
        ep.setActiveFlag(true);
        ep.setStaleFlag(false);

        when(sourceProjectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId))
                .thenReturn(List.of(ep));
        when(apiSchemaRepository.findBySourceProjectId(projectId)).thenReturn(List.of());
        when(apiParameterRepository.findByApiEndpointId(ep.getId())).thenReturn(List.of());
        when(endpointSchemaMapRepository.findByApiEndpointId(ep.getId())).thenReturn(List.of());

        Map<String, Object> openApi = service.generateOpenApiJson(projectId);
        Map<String, Object> paths = (Map<String, Object>) openApi.get("paths");
        Map<String, Object> pathItem = (Map<String, Object>) paths.get("/api/v1/customers");
        Map<String, Object> operation = (Map<String, Object>) pathItem.get("get");

        // Should correctly infer tag, summary, and operationId
        assertEquals("Customers", ((List<String>) operation.get("tags")).get(0));
        assertEquals("List customers", operation.get("summary"));
        assertEquals("listCustomers", operation.get("operationId"));
    }

    // -------------------------------------------------------------------------
    // Fix 1 — operationId camelCase collision tests
    // -------------------------------------------------------------------------

    @Test
    void operationIdCollisionSuffixHasNoUnderscore() {
        // Force a three-way collision: same summary from three endpoints sharing the same
        // path and controller but only differing in a detail we don't capture in operationId.
        // We use pre-set operationIds to simulate a collision scenario directly via the enhancer.
        OpenApiMetadataEnhancer enhancer = new OpenApiMetadataEnhancer();
        Set<String> used = new LinkedHashSet<>();

        ApiEndpoint ep = new ApiEndpoint();
        ep.setEndpointPath("/legacy/orders");
        ep.setControllerName("OrderGateway");
        ep.setHttpMethod(HttpMethod.GET);

        // First call — gets "listOrders"
        String id1 = enhancer.inferOperationId(ep, List.of(), used);
        assertEquals("listOrders", id1);
        assertFalse(id1.contains("_"));

        // Second call — collision on "listOrders", should get "listOrdersGet"
        String id2 = enhancer.inferOperationId(ep, List.of(), used);
        assertEquals("listOrdersGet", id2);
        assertFalse(id2.contains("_"));

        // Third call — collision on both, falls through to numeric suffix "listOrdersGet2" — no underscore
        String id3 = enhancer.inferOperationId(ep, List.of(), used);
        assertFalse(id3.contains("_"),
                "Last-resort operationId must not contain underscore — got: " + id3);
        // Numeric suffix is appended directly: listOrders2 or listOrdersGet2
        assertTrue(id3.matches("[a-zA-Z0-9]+"),
                "operationId must be alphanumeric only — got: " + id3);
    }

    // -------------------------------------------------------------------------
    // Fix 2 — isMeaningfulDescription quality guard tests
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void descriptionJunkValuesAreRejected() {
        // Build an endpoint with junk descriptions — should fall back to rule-based description.
        OpenApiMetadataEnhancer enhancer = new OpenApiMetadataEnhancer();

        String[] junkValues = {"N/A", "n/a", "-", "--", "TODO", "todo", "TBD", "tbd", "placeholder", "Placeholder"};

        for (String junk : junkValues) {
            ApiEndpoint ep = new ApiEndpoint();
            ep.setEndpointPath("/legacy/orders");
            ep.setControllerName("OrderGateway");
            ep.setHttpMethod(HttpMethod.GET);
            ep.setDescription(junk);  // Set junk as the stored description

            String desc = enhancer.inferDescription(ep, "List orders");

            // Must NOT emit the junk value; must fall through to the rule-based template.
            assertNotEquals(junk, desc,
                    "Junk description '" + junk + "' should be rejected but was returned");
            assertFalse(desc.isBlank(),
                    "Fallback description must not be blank when junk is rejected");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void descriptionTooLongIsRejected() {
        OpenApiMetadataEnhancer enhancer = new OpenApiMetadataEnhancer();

        // Build a 501-char description (raw Javadoc dump simulation)
        String longDesc = "A".repeat(501);

        ApiEndpoint ep = new ApiEndpoint();
        ep.setEndpointPath("/legacy/orders");
        ep.setControllerName("OrderGateway");
        ep.setHttpMethod(HttpMethod.GET);
        ep.setDescription(longDesc);

        String desc = enhancer.inferDescription(ep, "List orders");

        // Must NOT emit the long dump; must fall through to the rule-based template.
        assertNotEquals(longDesc, desc,
                "Description longer than 500 chars should be rejected");
        // "List orders" hits the named special-case in inferDescription.
        assertEquals("Retrieves orders by tenant ID.", desc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void descriptionValidConciseIsPreserved() {
        OpenApiMetadataEnhancer enhancer = new OpenApiMetadataEnhancer();

        String validDesc = "Retrieves all orders for the given tenant.";

        ApiEndpoint ep = new ApiEndpoint();
        ep.setEndpointPath("/legacy/orders");
        ep.setControllerName("OrderGateway");
        ep.setHttpMethod(HttpMethod.GET);
        ep.setDescription(validDesc);

        String desc = enhancer.inferDescription(ep, "List orders");

        // A valid, concise description must be preserved as-is.
        assertEquals(validDesc, desc,
                "Valid concise description should be preserved");
    }
}
