package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.AiOptimizationProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.CreateTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.res.TestCaseDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseassertion.req.CreateTestCaseAssertionRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.req.CreateTestCaseInputRequest;
import com.aitoolcheck.ai_toolcheck1_backend.enums.AssertionType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator;
import com.aitoolcheck.ai_toolcheck1_backend.enums.GeneratedBy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
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
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq.AiTaskProducer;
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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCaseServiceImplTest {

    private TestCaseRepository testCaseRepository;
    private ProjectAccessService projectAccessService;
    private ApiEndpointRepository apiEndpointRepository;
    private ApiDocumentVersionRepository apiDocumentVersionRepository;
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

        service = new TestCaseServiceImpl(
                testCaseRepository,
                mock(TestCaseAssertionRepository.class),
                apiEndpointRepository,
                apiDocumentVersionRepository,
                mock(AiJobLogRepository.class),
                mock(AiTaskProducer.class),
                mock(AiModelRouterService.class),
                mock(AiJsonParserService.class),
                projectAccessService,
                mock(AiSkillRepository.class),
                new ObjectMapper(),
                mock(ApplicationContext.class),
                mock(AiPayloadOptimizerService.class),
                mock(AiOptimizationProperties.class));

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

        ApiDocumentVersion docVersion = new ApiDocumentVersion();
        docVersion.setId(docVersionId);
        docVersion.setVersionNo(1);

        when(projectAccessService.requireCanCreateTestCase(projectId)).thenReturn(project);
        when(apiEndpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(testCaseRepository.existsBySourceProject_IdAndCaseNameIgnoreCaseAndDeletedFlagFalse(any(), any()))
                .thenReturn(false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Existing test (regression)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void deleteUsesProjectLevelDeleteGuard() {
        UUID testCaseId = UUID.randomUUID();
        TestCase testCase = new TestCase();
        testCase.setId(testCaseId);
        testCase.setSourceProject(project);
        testCase.setDeletedFlag(false);

        when(testCaseRepository.findByIdAndDeletedFlagFalse(testCaseId)).thenReturn(Optional.of(testCase));

        service.delete(testCaseId);

        verify(projectAccessService).requireCanDeleteTestCase(projectId);
        verify(testCaseRepository).save(testCase);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FIX — manual create test case
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Core regression test: manual POST /v1/test-cases with valid JSON object fields
     * must succeed and must NOT throw a Jackson JsonNode type error.
     */
    @Test
    void createManualTestCase_withQueryParamsAndHeaders_succeeds() {
        TestCase savedTestCase = buildSavedTestCase(
                "Manual - GET greeting",
                Map.of("name", "ChatGPT"),
                Map.of("Accept", "application/json"),
                null);

        when(testCaseRepository.save(any(TestCase.class))).thenReturn(savedTestCase);

        CreateTestCaseRequest request = buildCreateRequest(
                "Manual - GET greeting",
                Map.of("name", "ChatGPT"),
                Map.of("Accept", "application/json"),
                null);

        TestCaseDetailResponse response = assertDoesNotThrow(
                () -> service.create(request),
                "create() must not throw — particularly must not throw JsonNode type error");

        assertNotNull(response);
        assertNotNull(response.getInput());
        assertEquals(HttpMethod.GET, response.getInput().getHttpMethod());
        assertEquals("/greeting", response.getInput().getRequestPath());
        // queryParamsJson must be returned as a real Map, not JsonNode metadata
        assertThat(response.getInput().getQueryParamsJson()).containsEntry("name", "ChatGPT");
        assertThat(response.getInput().getHeadersJson()).containsEntry("Accept", "application/json");
    }

    @Test
    void createManualTestCase_withNullRequestBodyJson_succeedsForGetRequest() {
        TestCase savedTestCase = buildSavedTestCase("Manual - GET no body", null, null, null);
        when(testCaseRepository.save(any(TestCase.class))).thenReturn(savedTestCase);

        CreateTestCaseRequest request = buildCreateRequest("Manual - GET no body", null, null, null);

        TestCaseDetailResponse response = assertDoesNotThrow(() -> service.create(request));

        assertNotNull(response);
        assertNotNull(response.getInput());
        // Null JSON fields must be returned as null (not empty Map or JsonNode metadata)
        assertThat(response.getInput().getQueryParamsJson()).isNull();
        assertThat(response.getInput().getRequestBodyJson()).isNull();
    }

    @Test
    void createManualTestCase_withEmptyQueryParams_succeeds() {
        // {} empty object must be valid
        TestCase savedTestCase = buildSavedTestCase("Manual - empty params", Map.of(), null, null);
        when(testCaseRepository.save(any(TestCase.class))).thenReturn(savedTestCase);

        CreateTestCaseRequest request = buildCreateRequest("Manual - empty params", Map.of(), null, null);

        assertDoesNotThrow(() -> service.create(request));
    }

    @Test
    void responseSerializesQueryParamsAsRealMapNotJsonNodeMetadata() {
        // When the service reads back a stored JSON string, it must return Map<String,Object>
        // and NOT internal JsonNode bean fields like "array", "boolean", "nodeType", etc.
        TestCase savedTestCase = buildSavedTestCase(
                "Serialization check",
                Map.of("name", "ChatGPT", "page", 1),
                null, null);

        when(testCaseRepository.save(any(TestCase.class))).thenReturn(savedTestCase);

        CreateTestCaseRequest request = buildCreateRequest(
                "Serialization check",
                Map.of("name", "ChatGPT", "page", 1),
                null, null);

        TestCaseDetailResponse response = service.create(request);

        Map<String, Object> qp = response.getInput().getQueryParamsJson();
        assertNotNull(qp, "queryParamsJson must not be null");
        // Must be real values, not JsonNode internal fields
        assertThat(qp).doesNotContainKey("array");
        assertThat(qp).doesNotContainKey("nodeType");
        assertThat(qp).doesNotContainKey("object");
        assertThat(qp).doesNotContainKey("boolean");
        assertThat(qp).containsEntry("name", "ChatGPT");
    }

    @Test
    void saveAiGeneratedTestCases_infersMetadataCorrectly() {
        // Prepare request
        com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiGeneratedTestCaseRequest request = 
            new com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiGeneratedTestCaseRequest();
        
        com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseItemDto postPositive = 
            com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseItemDto.builder()
                .testName("POST Positive success")
                .caseType("SUCCESS")
                .httpMethod(HttpMethod.POST)
                .url("/legacy/customers")
                .build();

        com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseItemDto postValidation = 
            com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseItemDto.builder()
                .testName("POST Validation bad request")
                .caseType("VALIDATION_ERROR")
                .httpMethod(HttpMethod.POST)
                .url("/legacy/customers")
                .build();

        com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseItemDto getNormal = 
            com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseItemDto.builder()
                .testName("GET list")
                .caseType("SUCCESS")
                .httpMethod(HttpMethod.GET)
                .url("/legacy/customers")
                .build();

        com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseItemDto getFakePositive = 
            com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseItemDto.builder()
                .testName("GET by fake ID positive")
                .caseType("SUCCESS")
                .httpMethod(HttpMethod.GET)
                .url("/legacy/customers/CUSTOMER_ABC_123")
                .build();

        request.setTestCases(List.of(postPositive, postValidation, getNormal, getFakePositive));

        org.mockito.ArgumentCaptor<TestCase> captor = org.mockito.ArgumentCaptor.forClass(TestCase.class);

        service.saveAiGeneratedTestCases(request, endpointId, UUID.randomUUID());

        verify(testCaseRepository, org.mockito.Mockito.times(4)).save(captor.capture());

        List<TestCase> savedCases = captor.getAllValues();
        
        // Assert POST positive
        TestCase tc1 = savedCases.stream().filter(c -> c.getCaseName().equals("POST Positive success")).findFirst().orElseThrow();
        assertThat(tc1.getRequiresWrite()).isTrue();
        assertThat(tc1.getCleanupRequired()).isTrue();
        assertThat(tc1.getActiveFlag()).isTrue();

        // Assert POST validation
        TestCase tc2 = savedCases.stream().filter(c -> c.getCaseName().equals("POST Validation bad request")).findFirst().orElseThrow();
        assertThat(tc2.getRequiresWrite()).isTrue();
        assertThat(tc2.getCleanupRequired()).isFalse();
        assertThat(tc2.getActiveFlag()).isTrue();

        // Assert GET normal
        TestCase tc3 = savedCases.stream().filter(c -> c.getCaseName().equals("GET list")).findFirst().orElseThrow();
        assertThat(tc3.getRequiresWrite()).isFalse();
        assertThat(tc3.getCleanupRequired()).isFalse();
        assertThat(tc3.getActiveFlag()).isTrue();

        // Assert GET fake positive
        TestCase tc4 = savedCases.stream().filter(c -> c.getCaseName().equals("GET by fake ID positive")).findFirst().orElseThrow();
        assertThat(tc4.getRequiresWrite()).isFalse();
        assertThat(tc4.getCleanupRequired()).isFalse();
        assertThat(tc4.getActiveFlag()).isFalse(); // Deactivated due to fake path parameter in positive GET
    }

    @Test
    void saveAiGeneratedTestCases_sanitizesMalformedGetPathTestCases() {
        // Prepare request
        com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiGeneratedTestCaseRequest request = 
            new com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiGeneratedTestCaseRequest();
        
        com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseAssertionDto statusAssertion =
            com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseAssertionDto.builder()
                .assertionType("STATUS_CODE")
                .expectedValue("400")
                .build();

        com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseAssertionDto msgAssertion =
            com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseAssertionDto.builder()
                .assertionType("JSON_PATH")
                .jsonPath("$.message")
                .comparisonOperator("EQUALS")
                .expectedValue("Invalid format")
                .build();

        com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseItemDto getMalformed = 
            com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseItemDto.builder()
                .testName("GET with malformed special characters")
                .caseType("VALIDATION_ERROR")
                .httpMethod(HttpMethod.GET)
                .url("/legacy/customers/CUST_!@#$%^")
                .assertions(new java.util.ArrayList<>(List.of(statusAssertion, msgAssertion)))
                .build();

        request.setTestCases(List.of(getMalformed));

        org.mockito.ArgumentCaptor<TestCase> captor = org.mockito.ArgumentCaptor.forClass(TestCase.class);

        service.saveAiGeneratedTestCases(request, endpointId, UUID.randomUUID());

        verify(testCaseRepository).save(captor.capture());

        TestCase savedCase = captor.getValue();
        assertThat(savedCase.getTestCaseInput().getRequestPath()).isEqualTo("/legacy/customers/UNKNOWN_CUSTOMER_ID");
        assertThat(savedCase.getCaseName()).contains("unknown ID");
        
        // Assert assertions were updated to expect 404 and "Not Found"
        List<TestCaseAssertion> assertions = savedCase.getTestCaseAssertions();
        assertThat(assertions).hasSize(2);
        
        TestCaseAssertion status = assertions.stream().filter(a -> a.getAssertionType() == AssertionType.STATUS_CODE).findFirst().orElseThrow();
        assertThat(status.getExpectedValue()).isEqualTo("404");

        TestCaseAssertion msg = assertions.stream().filter(a -> a.getAssertionType() == AssertionType.JSON_PATH).findFirst().orElseThrow();
        assertThat(msg.getTargetPath()).isEqualTo("$.error");
        assertThat(msg.getExpectedValue()).isEqualTo("Not Found");
        assertThat(msg.getOperator()).isEqualTo(ComparisonOperator.CONTAINS);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private CreateTestCaseRequest buildCreateRequest(
            String caseName,
            Map<String, Object> queryParams,
            Map<String, Object> headers,
            Map<String, Object> requestBody) {

        CreateTestCaseInputRequest input = CreateTestCaseInputRequest.builder()
                .httpMethod(HttpMethod.GET)
                .requestPath("/greeting")
                .queryParamsJson(queryParams)
                .headersJson(headers)
                .requestBodyJson(requestBody)
                .contentType("application/json")
                .timeoutMs(30000)
                .build();

        CreateTestCaseAssertionRequest statusAssertion = CreateTestCaseAssertionRequest.builder()
                .assertionType(AssertionType.STATUS_CODE)
                .targetPath("")
                .operator(ComparisonOperator.EQUALS)
                .expectedValue("200")
                .enabledFlag(true)
                .sortOrder(1)
                .build();

        return CreateTestCaseRequest.builder()
                .projectId(projectId)
                .apiEndpointId(endpointId)
                .apiDocumentVersionId(null)   // optional — skips version ownership check
                .caseName(caseName)
                .description("Unit test")
                .generatedBy(GeneratedBy.USER)
                .activeFlag(true)
                .requiresWrite(false)
                .cleanupRequired(false)
                .input(input)
                .assertions(List.of(statusAssertion))
                .build();
    }

    private TestCase buildSavedTestCase(
            String caseName,
            Map<String, Object> queryParams,
            Map<String, Object> headers,
            Map<String, Object> requestBody) {

        ObjectMapper om = new ObjectMapper();

        String queryStr = null, headersStr = null, bodyStr = null;
        try {
            if (queryParams != null) queryStr = om.writeValueAsString(queryParams);
            if (headers != null) headersStr = om.writeValueAsString(headers);
            if (requestBody != null) bodyStr = om.writeValueAsString(requestBody);
        } catch (Exception ignored) {}

        TestCaseInput input = TestCaseInput.builder()
                .id(UUID.randomUUID())
                .httpMethod(HttpMethod.GET)
                .requestPath("/greeting")
                .queryParamsJson(queryStr)
                .headersJson(headersStr)
                .requestBodyJson(bodyStr)
                .contentType("application/json")
                .timeoutMs(30000)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        TestCaseAssertion assertion = TestCaseAssertion.builder()
                .id(UUID.randomUUID())
                .assertionType(AssertionType.STATUS_CODE)
                .operator(ComparisonOperator.EQUALS)
                .expectedValue("200")
                .enabledFlag(true)
                .sortOrder(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        TestCase tc = new TestCase();
        tc.setId(UUID.randomUUID());
        tc.setCaseName(caseName);
        tc.setSourceProject(project);
        tc.setApiEndpoint(endpoint);
        tc.setGeneratedBy(GeneratedBy.USER);
        tc.setActiveFlag(true);
        tc.setDeletedFlag(false);
        tc.setRequiresWrite(false);
        tc.setCleanupRequired(false);
        tc.setCreatedAt(LocalDateTime.now());
        tc.setUpdatedAt(LocalDateTime.now());
        tc.assignInput(input);
        tc.replaceAssertions(List.of(assertion));

        // Wire back-references so toDetailResponse works
        input.setTestCase(tc);
        assertion.setTestCase(tc);

        return tc;
    }
}
