package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.AiOptimizationProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiGeneratedTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.CreateTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.res.TestCaseDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseassertion.req.CreateTestCaseAssertionRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.req.CreateTestCaseInputRequest;
import com.aitoolcheck.ai_toolcheck1_backend.enums.AssertionType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator;
import com.aitoolcheck.ai_toolcheck1_backend.enums.GeneratedBy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiDocument;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiDocumentVersion;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCase;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseAssertion;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput;
import com.aitoolcheck.ai_toolcheck1_backend.repository.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJsonParserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiModelRouterService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiPayloadOptimizerService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestCaseService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.ai.AiPromptConstants;
import com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq.AiTaskProducer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TestCaseServiceImplTest {

    // ── Shared OpenAPI JSON fixture ──────────────────────────────────────────
    private static final String SAMPLE_OPENAPI = """
            {
              "openapi": "3.0.3",
              "info": {"title": "Test API", "version": "1.0"},
              "paths": {
                "/users/{id}": {
                  "get": {
                    "operationId": "getUserById",
                    "summary": "Get user by ID",
                    "parameters": [{"name": "id", "in": "path", "required": true,
                                    "schema": {"type": "integer"}}],
                    "responses": {
                      "200": {"description": "OK",
                              "content": {"application/json": {"schema": {
                                "$ref": "#/components/schemas/UserDto"}}}},
                      "404": {"description": "Not Found"}
                    }
                  }
                },
                "/users": {
                  "post": {
                    "operationId": "createUser",
                    "requestBody": {"content": {"application/json": {"schema": {
                      "$ref": "#/components/schemas/CreateUserRequest"}}}},
                    "responses": {"201": {"description": "Created"}}
                  }
                },
                "/greeting": {
                  "get": {
                    "operationId": "greeting",
                    "responses": {"200": {"description": "OK"}}
                  }
                }
              },
              "components": {
                "schemas": {
                  "UserDto": {
                    "type": "object",
                    "required": ["id", "username"],
                    "properties": {
                      "id":       {"type": "integer"},
                      "username": {"type": "string"},
                      "email":    {"type": "string"}
                    }
                  },
                  "CreateUserRequest": {
                    "type": "object",
                    "required": ["username", "email"],
                    "properties": {
                      "username": {"type": "string"},
                      "email":    {"type": "string"}
                    }
                  },
                  "AdminDto": {
                    "type": "object",
                    "properties": {"adminId": {"type": "string"}}
                  }
                }
              }
            }
            """;

    // ── Mocks & SUT ──────────────────────────────────────────────────────────
    private TestCaseRepository testCaseRepository;
    private ProjectAccessService projectAccessService;
    private ApiEndpointRepository apiEndpointRepository;
    private ApiDocumentVersionRepository apiDocumentVersionRepository;
    private AiModelRouterService aiModelRouterService;
    private AiJsonParserService aiJsonParserService;
    private AiJobLogRepository aiJobLogRepository;
    private ApplicationContext applicationContext;
    private AiOptimizationProperties aiOptimizationProperties;
    private AiPayloadOptimizerService aiPayloadOptimizerService;
    private TestCaseServiceImpl service;

    private UUID projectId;
    private UUID endpointId;
    private UUID docVersionId;
    private SourceProject project;
    private ApiEndpoint endpoint;

    @BeforeEach
    void setUp() {
        testCaseRepository = mock(TestCaseRepository.class);
        projectAccessService = mock(ProjectAccessService.class);
        apiEndpointRepository = mock(ApiEndpointRepository.class);
        apiDocumentVersionRepository = mock(ApiDocumentVersionRepository.class);
        aiModelRouterService = mock(AiModelRouterService.class);
        aiJsonParserService = mock(AiJsonParserService.class);
        aiJobLogRepository = mock(AiJobLogRepository.class);
        applicationContext = mock(ApplicationContext.class);
        aiOptimizationProperties = mock(AiOptimizationProperties.class);
        aiPayloadOptimizerService = mock(AiPayloadOptimizerService.class);

        service = new TestCaseServiceImpl(
                testCaseRepository,
                mock(TestCaseAssertionRepository.class),
                apiEndpointRepository,
                apiDocumentVersionRepository,
                aiJobLogRepository,
                mock(AiTaskProducer.class),
                aiModelRouterService,
                aiJsonParserService,
                projectAccessService,
                mock(AiSkillRepository.class),
                new ObjectMapper(),
                applicationContext,
                aiPayloadOptimizerService,
                aiOptimizationProperties);

        projectId = UUID.randomUUID();
        endpointId = UUID.randomUUID();
        docVersionId = UUID.randomUUID();

        project = new SourceProject();
        project.setId(projectId);

        endpoint = new ApiEndpoint();
        endpoint.setId(endpointId);
        endpoint.setSourceProject(project);
        endpoint.setHttpMethod(HttpMethod.GET);
        endpoint.setEndpointPath("/greeting");
        endpoint.setActiveFlag(true);
        endpoint.setStaleFlag(false);

        when(projectAccessService.requireCanCreateTestCase(projectId)).thenReturn(project);
        when(apiEndpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(testCaseRepository.existsBySourceProject_IdAndCaseNameIgnoreCaseAndDeletedFlagFalse(any(), any()))
                .thenReturn(false);
    }

    // ── Regression: existing tests ───────────────────────────────────────────

    @Test
    void deleteUsesProjectLevelDeleteGuard() {
        UUID id = UUID.randomUUID();
        TestCase tc = new TestCase();
        tc.setId(id);
        tc.setSourceProject(project);
        tc.setDeletedFlag(false);
        when(testCaseRepository.findByIdAndDeletedFlagFalse(id)).thenReturn(Optional.of(tc));
        service.delete(id);
        verify(projectAccessService).requireCanDeleteTestCase(projectId);
        verify(testCaseRepository).save(tc);
    }

    @Test
    void createManualTestCase_withQueryParamsAndHeaders_succeeds() {
        TestCase saved = buildSavedTestCase("Manual", Map.of("name", "v"), Map.of("Accept", "application/json"), null);
        when(testCaseRepository.save(any())).thenReturn(saved);
        TestCaseDetailResponse r = assertDoesNotThrow(() ->
                service.create(buildCreateRequest("Manual", Map.of("name", "v"), Map.of("Accept", "application/json"), null)));
        assertThat(r.getInput().getQueryParamsJson()).containsEntry("name", "v");
    }

    @Test
    void createManualTestCase_withNullRequestBodyJson_succeedsForGetRequest() {
        when(testCaseRepository.save(any())).thenReturn(buildSavedTestCase("G", null, null, null));
        TestCaseDetailResponse r = assertDoesNotThrow(() -> service.create(buildCreateRequest("G", null, null, null)));
        assertThat(r.getInput().getQueryParamsJson()).isNull();
    }

    @Test
    void saveAiGeneratedTestCases_infersMetadataCorrectly() {
        var req = new AiGeneratedTestCaseRequest();
        var postPos = com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseItemDto.builder()
                .testName("POST Positive success").caseType("SUCCESS").httpMethod(HttpMethod.POST).url("/legacy/customers").build();
        var getFake = com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseItemDto.builder()
                .testName("GET fake").caseType("SUCCESS").httpMethod(HttpMethod.GET).url("/legacy/customers/CUSTOMER_ABC_123").build();
        req.setTestCases(List.of(postPos, getFake));
        var captor = org.mockito.ArgumentCaptor.forClass(TestCase.class);
        service.saveAiGeneratedTestCases(req, endpointId, UUID.randomUUID());
        verify(testCaseRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().stream().filter(c -> c.getCaseName().equals("GET fake")).findFirst().orElseThrow().getActiveFlag()).isFalse();
    }

    @Test
    void saveAiGeneratedTestCases_sanitizesMalformedGetPathTestCases() {
        var req = new AiGeneratedTestCaseRequest();
        var statusA = com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseAssertionDto.builder()
                .assertionType("STATUS_CODE").expectedValue("400").build();
        var msgA = com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseAssertionDto.builder()
                .assertionType("JSON_PATH").jsonPath("$.message").comparisonOperator("EQUALS").expectedValue("Invalid format").build();
        var item = com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseItemDto.builder()
                .testName("GET with malformed special characters").caseType("VALIDATION_ERROR")
                .httpMethod(HttpMethod.GET).url("/legacy/customers/CUST_!@#$%^")
                .assertions(new java.util.ArrayList<>(List.of(statusA, msgA))).build();
        req.setTestCases(List.of(item));
        var captor = org.mockito.ArgumentCaptor.forClass(TestCase.class);
        service.saveAiGeneratedTestCases(req, endpointId, UUID.randomUUID());
        verify(testCaseRepository).save(captor.capture());
        assertThat(captor.getValue().getTestCaseInput().getRequestPath()).isEqualTo("/legacy/customers/UNKNOWN_CUSTOMER_ID");
        assertThat(captor.getValue().getTestCaseAssertions().stream()
                .filter(a -> a.getAssertionType() == AssertionType.STATUS_CODE).findFirst().orElseThrow().getExpectedValue()).isEqualTo("404");
    }

    // ── Regression: prompt constant checks ──────────────────────────────────

    @Test
    void promptSkill2_usesNamedPlaceholdersAndNoStringFormat() {
        assertThat(AiPromptConstants.PROMPT_SKILL_2_GEN_TESTCASE).doesNotContain("%s");
        assertThat(AiPromptConstants.PROMPT_SKILL_2_GEN_TESTCASE).contains("{{OPENAPI_OPERATION_CONTEXT}}");
    }

    @Test
    void regression_agent1EnrichDocPrompt_unaffected() {
        assertThat(AiPromptConstants.ENRICH_DOC_SYSTEM_PROMPT).contains("%s");
        String f = String.format(AiPromptConstants.ENRICH_DOC_SYSTEM_PROMPT, "RAG", "META");
        assertThat(f).contains("RAG").contains("META");
    }

    // ── OpenAPI helper unit tests ────────────────────────────────────────────

    @Test
    void findOperationNode_matchesExactPath() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(SAMPLE_OPENAPI);
        JsonNode op = service.findOperationNode(root, "/users/{id}", "get");
        assertThat(op).isNotNull();
        assertThat(op.get("operationId").asText()).isEqualTo("getUserById");
    }

    @Test
    void findOperationNode_normalizesTrailingSlash() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(SAMPLE_OPENAPI);
        JsonNode op = service.findOperationNode(root, "/greeting/", "get");
        assertThat(op).isNotNull();
    }

    @Test
    void findOperationNode_returnsNullForMissingPath() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(SAMPLE_OPENAPI);
        assertThat(service.findOperationNode(root, "/nonexistent", "get")).isNull();
    }

    @Test
    void collectSchemaRefs_extractsDirectRefs() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(SAMPLE_OPENAPI);
        JsonNode op = service.findOperationNode(root, "/users/{id}", "get");
        java.util.Set<String> refs = service.collectSchemaRefs(op);
        assertThat(refs).contains("UserDto");
    }

    @Test
    void resolveRelatedSchemas_onlyIncludesReferencedSchemas() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(SAMPLE_OPENAPI);
        // GET /users/{id} references UserDto only
        JsonNode op = service.findOperationNode(root, "/users/{id}", "get");
        java.util.Set<String> refs = service.collectSchemaRefs(op);
        var resolved = service.resolveRelatedSchemas(root, refs, new java.util.HashSet<>());
        assertThat(resolved.has("UserDto")).isTrue();
        assertThat(resolved.has("AdminDto")).isFalse();   // unrelated schema excluded
        assertThat(resolved.has("CreateUserRequest")).isFalse();
    }

    @Test
    void buildCompactOpenApiContext_containsRequiredFields() throws Exception {
        ApiDocumentVersion version = new ApiDocumentVersion();
        version.setId(docVersionId);
        version.setVersionNo(1);
        version.setContentJson(SAMPLE_OPENAPI);

        endpoint.setHttpMethod(HttpMethod.GET);
        endpoint.setEndpointPath("/users/{id}");

        String context = service.buildCompactOpenApiContext(endpoint, version);
        assertThat(context).contains("AGENT_1_OPENAPI_DOCUMENT");
        assertThat(context).contains("/users/{id}");
        assertThat(context).contains("\"method\":\"get\"");
        assertThat(context).contains("getUserById");
        assertThat(context).contains("UserDto");
        assertThat(context).contains("username");   // schema property
        assertThat(context).doesNotContain("AdminDto");
    }

    @Test
    void buildCompactOpenApiContext_throwsWhenOperationNotFound() {
        ApiDocumentVersion version = new ApiDocumentVersion();
        version.setId(docVersionId);
        version.setContentJson(SAMPLE_OPENAPI);

        endpoint.setHttpMethod(HttpMethod.DELETE);
        endpoint.setEndpointPath("/nonexistent");

        assertThatThrownBy(() -> service.buildCompactOpenApiContext(endpoint, version))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("OpenAPI operation not found");
    }

    // ── New Agent 2 flow tests ───────────────────────────────────────────────

    @Test
    void generateTestCaseProcessing_requiresOpenApiDocument() {
        UUID jobId = UUID.randomUUID();
        when(applicationContext.getBean(TestCaseService.class)).thenReturn(service);
        when(apiDocumentVersionRepository
                .findByApiDocumentSourceProjectIdOrderByVersionNoDesc(projectId))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.generateTestCaseProcessing(endpointId.toString(), jobId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("No OpenAPI document found");

        verify(aiModelRouterService, never()).routeAndExecuteForSkill(any(), any(), any());
        verify(testCaseRepository, never()).save(any());
    }

    @Test
    void generateTestCaseProcessing_loadsLatestOpenApiDocument() {
        UUID jobId = UUID.randomUUID();

        ApiDocumentVersion v1 = makeVersion(1, SAMPLE_OPENAPI);
        ApiDocumentVersion v2 = makeVersion(2, SAMPLE_OPENAPI);   // latest

        when(applicationContext.getBean(TestCaseService.class)).thenReturn(service);
        // Repository returns desc order: v2 first
        when(apiDocumentVersionRepository
                .findByApiDocumentSourceProjectIdOrderByVersionNoDesc(projectId))
                .thenReturn(List.of(v2, v1));

        String aiJson = "{\"test_cases\":[]}";
        when(aiModelRouterService.routeAndExecuteForSkill(eq("GENERATE_TEST_CASE"), any(), any()))
                .thenReturn(aiJson);
        AiGeneratedTestCaseRequest parsed = new AiGeneratedTestCaseRequest();
        parsed.setTestCases(List.of());
        when(aiJsonParserService.parseTestCaseRequest(aiJson)).thenReturn(parsed);

        // endpoint path must exist in OpenAPI
        endpoint.setEndpointPath("/greeting");
        endpoint.setHttpMethod(HttpMethod.GET);

        assertDoesNotThrow(() -> service.generateTestCaseProcessing(endpointId.toString(), jobId));
        // AI router must have been called (meaning v2 was loaded and matched)
        verify(aiModelRouterService).routeAndExecuteForSkill(eq("GENERATE_TEST_CASE"), any(), any());
    }

    @Test
    void generateTestCaseProcessing_matchesEndpointToOpenApiOperation() {
        ApiDocumentVersion version = makeVersion(1, SAMPLE_OPENAPI);
        when(apiDocumentVersionRepository
                .findByApiDocumentSourceProjectIdOrderByVersionNoDesc(projectId))
                .thenReturn(List.of(version));
        when(applicationContext.getBean(TestCaseService.class)).thenReturn(service);

        endpoint.setHttpMethod(HttpMethod.GET);
        endpoint.setEndpointPath("/users/{id}");

        String aiJson = "{\"test_cases\":[]}";
        when(aiModelRouterService.routeAndExecuteForSkill(eq("GENERATE_TEST_CASE"), any(), any()))
                .thenReturn(aiJson);
        AiGeneratedTestCaseRequest parsed = new AiGeneratedTestCaseRequest();
        parsed.setTestCases(List.of());
        when(aiJsonParserService.parseTestCaseRequest(aiJson)).thenReturn(parsed);

        assertDoesNotThrow(() -> service.generateTestCaseProcessing(endpointId.toString(), UUID.randomUUID()));
        verify(aiModelRouterService).routeAndExecuteForSkill(eq("GENERATE_TEST_CASE"), any(), any());
    }

    @Test
    void generateTestCasePrompt_containsCompactOpenApiOperationContext() throws Exception {
        ApiDocumentVersion version = makeVersion(1, SAMPLE_OPENAPI);
        endpoint.setHttpMethod(HttpMethod.GET);
        endpoint.setEndpointPath("/users/{id}");

        String context = service.buildCompactOpenApiContext(endpoint, version);
        String prompt = service.buildGenerateTestCasePrompt(context, endpointId.toString(), UUID.randomUUID());

        assertThat(prompt).contains("AGENT_1_OPENAPI_DOCUMENT");
        assertThat(prompt).contains("/users/{id}");
        assertThat(prompt).contains("getUserById");
        assertThat(prompt).contains("username");   // property from UserDto schema
        assertThat(prompt).doesNotContain("{{OPENAPI_OPERATION_CONTEXT}}");
    }

    @Test
    void generateTestCasePrompt_doesNotIncludeUnrelatedSchemas() throws Exception {
        ApiDocumentVersion version = makeVersion(1, SAMPLE_OPENAPI);
        endpoint.setHttpMethod(HttpMethod.GET);
        endpoint.setEndpointPath("/users/{id}");  // refs UserDto only

        String context = service.buildCompactOpenApiContext(endpoint, version);
        assertThat(context).doesNotContain("AdminDto");
        assertThat(context).doesNotContain("adminId");
    }

    @Test
    void generateTestCaseProcessing_rejectsMissingOperationInOpenApi() {
        UUID jobId = UUID.randomUUID();
        ApiDocumentVersion version = makeVersion(1, SAMPLE_OPENAPI);
        when(apiDocumentVersionRepository
                .findByApiDocumentSourceProjectIdOrderByVersionNoDesc(projectId))
                .thenReturn(List.of(version));
        when(applicationContext.getBean(TestCaseService.class)).thenReturn(service);

        endpoint.setHttpMethod(HttpMethod.DELETE);
        endpoint.setEndpointPath("/nonexistent-path");

        assertThatThrownBy(() -> service.generateTestCaseProcessing(endpointId.toString(), jobId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("OpenAPI operation not found");

        verify(aiModelRouterService, never()).routeAndExecuteForSkill(any(), any(), any());
        verify(testCaseRepository, never()).save(any());
    }

    @Test
    void generateTestCaseProcessing_providerFailureSavesNoTestCases() {
        UUID jobId = UUID.randomUUID();
        ApiDocumentVersion version = makeVersion(1, SAMPLE_OPENAPI);
        when(apiDocumentVersionRepository
                .findByApiDocumentSourceProjectIdOrderByVersionNoDesc(projectId))
                .thenReturn(List.of(version));
        when(applicationContext.getBean(TestCaseService.class)).thenReturn(service);

        endpoint.setHttpMethod(HttpMethod.GET);
        endpoint.setEndpointPath("/greeting");

        when(aiModelRouterService.routeAndExecuteForSkill(eq("GENERATE_TEST_CASE"), any(), any()))
                .thenThrow(new RuntimeException("LLM timeout"));

        assertThatThrownBy(() -> service.generateTestCaseProcessing(endpointId.toString(), jobId))
                .hasMessageContaining("AI provider failed");

        verify(testCaseRepository, never()).save(any());
    }

    @Test
    void generateTestCaseProcessing_reachesRouterAfterPromptBuild() {
        UUID jobId = UUID.randomUUID();
        ApiDocumentVersion version = makeVersion(1, SAMPLE_OPENAPI);
        when(apiDocumentVersionRepository
                .findByApiDocumentSourceProjectIdOrderByVersionNoDesc(projectId))
                .thenReturn(List.of(version));
        when(applicationContext.getBean(TestCaseService.class)).thenReturn(service);

        endpoint.setHttpMethod(HttpMethod.GET);
        endpoint.setEndpointPath("/greeting");

        String aiJson = "{\"test_cases\":[]}";
        when(aiModelRouterService.routeAndExecuteForSkill(eq("GENERATE_TEST_CASE"), any(), any()))
                .thenReturn(aiJson);
        AiGeneratedTestCaseRequest parsed = new AiGeneratedTestCaseRequest();
        parsed.setTestCases(List.of());
        when(aiJsonParserService.parseTestCaseRequest(aiJson)).thenReturn(parsed);

        String result = service.generateTestCaseProcessing(endpointId.toString(), jobId);
        assertEquals(aiJson, result);
        verify(aiModelRouterService).routeAndExecuteForSkill(eq("GENERATE_TEST_CASE"), any(), any());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ApiDocumentVersion makeVersion(int versionNo, String contentJson) {
        ApiDocumentVersion v = new ApiDocumentVersion();
        v.setId(UUID.randomUUID());
        v.setVersionNo(versionNo);
        v.setContentJson(contentJson);
        ApiDocument doc = new ApiDocument();
        doc.setSourceProject(project);
        v.setApiDocument(doc);
        return v;
    }

    private CreateTestCaseRequest buildCreateRequest(String name, Map<String, Object> qp,
            Map<String, Object> h, Map<String, Object> body) {
        CreateTestCaseInputRequest input = CreateTestCaseInputRequest.builder()
                .httpMethod(HttpMethod.GET).requestPath("/greeting")
                .queryParamsJson(qp).headersJson(h).requestBodyJson(body)
                .contentType("application/json").timeoutMs(30000).build();
        CreateTestCaseAssertionRequest assertion = CreateTestCaseAssertionRequest.builder()
                .assertionType(AssertionType.STATUS_CODE).targetPath("")
                .operator(ComparisonOperator.EQUALS).expectedValue("200")
                .enabledFlag(true).sortOrder(1).build();
        return CreateTestCaseRequest.builder()
                .projectId(projectId).apiEndpointId(endpointId)
                .caseName(name).description("unit test").generatedBy(GeneratedBy.USER)
                .activeFlag(true).requiresWrite(false).cleanupRequired(false)
                .input(input).assertions(List.of(assertion)).build();
    }

    private TestCase buildSavedTestCase(String name, Map<String, Object> qp,
            Map<String, Object> h, Map<String, Object> body) {
        ObjectMapper om = new ObjectMapper();
        String qs = null, hs = null, bs = null;
        try {
            if (qp != null) qs = om.writeValueAsString(qp);
            if (h != null)  hs = om.writeValueAsString(h);
            if (body != null) bs = om.writeValueAsString(body);
        } catch (Exception ignored) {}
        TestCaseInput input = TestCaseInput.builder().id(UUID.randomUUID())
                .httpMethod(HttpMethod.GET).requestPath("/greeting")
                .queryParamsJson(qs).headersJson(hs).requestBodyJson(bs)
                .contentType("application/json").timeoutMs(30000)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        TestCaseAssertion assertion = TestCaseAssertion.builder().id(UUID.randomUUID())
                .assertionType(AssertionType.STATUS_CODE).operator(ComparisonOperator.EQUALS)
                .expectedValue("200").enabledFlag(true).sortOrder(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        TestCase tc = new TestCase();
        tc.setId(UUID.randomUUID()); tc.setCaseName(name);
        tc.setSourceProject(project); tc.setApiEndpoint(endpoint);
        tc.setGeneratedBy(GeneratedBy.USER); tc.setActiveFlag(true);
        tc.setDeletedFlag(false); tc.setRequiresWrite(false); tc.setCleanupRequired(false);
        tc.setCreatedAt(LocalDateTime.now()); tc.setUpdatedAt(LocalDateTime.now());
        tc.assignInput(input); tc.replaceAssertions(List.of(assertion));
        input.setTestCase(tc); assertion.setTestCase(tc);
        return tc;
    }
}
