package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.CreateTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.UpdateTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.res.TestCaseDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.res.TestCaseResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseassertion.req.CreateTestCaseAssertionRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseassertion.req.UpdateTestCaseAssertionRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseassertion.res.TestCaseAssertionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.req.CreateTestCaseInputRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.req.UpdateTestCaseInputRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.res.TestCaseInputResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.CaseType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.GeneratedBy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.PriorityLevel;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiDocumentVersion;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCase;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseAssertion;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiDocumentVersionRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestCaseRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestCaseService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TestCaseServiceImpl implements TestCaseService {

    private final TestCaseRepository testCaseRepository;
    private final SourceProjectRepository sourceProjectRepository;
    private final ApiEndpointRepository apiEndpointRepository;
    private final ApiDocumentVersionRepository apiDocumentVersionRepository;
    private final JsonMapper jsonMapper;
    private final ProjectAccessService projectAccessService;

    @Override
    @Transactional
    public TestCaseDetailResponse create(CreateTestCaseRequest request) {
        SourceProject sourceProject = projectAccessService.requireCanCreateTestCase(request.getProjectId());

        String caseName = normalizeRequiredText(request.getCaseName(), "caseName");

        if (testCaseRepository.existsBySourceProject_IdAndCaseNameIgnoreCaseAndDeletedFlagFalse(
                request.getProjectId(),
                caseName
        )) {
            throw new BadRequestException(
                    "TestCase already exists with caseName '" + caseName
                            + "' in project id: " + request.getProjectId()
            );
        }

        ApiEndpoint apiEndpoint = resolveApiEndpointOrNull(request.getApiEndpointId(), sourceProject.getId());
        ApiDocumentVersion apiDocumentVersion = resolveApiDocumentVersionOrNull(
                request.getApiDocumentVersionId(),
                sourceProject.getId()
        );

        validateCreateInput(request.getInput());
        validateCreateAssertions(request.getAssertions());

        TestCase testCase = TestCase.builder()
                .sourceProject(sourceProject)
                .apiEndpoint(apiEndpoint)
                .apiDocumentVersion(apiDocumentVersion)
                .caseCode(normalizeOptionalText(request.getCaseCode()))
                .caseName(caseName)
                .description(normalizeOptionalText(request.getDescription()))
                .caseType(request.getCaseType() != null ? request.getCaseType() : CaseType.POSITIVE)
                .priorityLevel(request.getPriorityLevel() != null ? request.getPriorityLevel() : PriorityLevel.MEDIUM)
                .generatedBy(request.getGeneratedBy() != null ? request.getGeneratedBy() : GeneratedBy.USER)
                .activeFlag(request.getActiveFlag() != null ? request.getActiveFlag() : true)
                .requiresWrite(request.getRequiresWrite() != null ? request.getRequiresWrite() : false)
                .cleanupRequired(request.getCleanupRequired() != null ? request.getCleanupRequired() : false)
                .deletedFlag(false)
                .build();

        testCase.assignInput(toInputEntity(request.getInput()));
        testCase.replaceAssertions(toAssertionEntities(request.getAssertions()));

        TestCase saved = testCaseRepository.save(testCase);
        return toDetailResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TestCaseResponse> getByProjectId(UUID projectId) {
        projectAccessService.requireCanViewProject(projectId);

        return testCaseRepository
                .findBySourceProject_IdAndDeletedFlagFalseOrderByUpdatedAtDesc(projectId)
                .stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TestCaseDetailResponse getById(UUID id) {
        TestCase testCase = findActiveTestCaseOrThrow(id);
        projectAccessService.requireCanViewProject(testCase.getSourceProject().getId());
        return toDetailResponse(testCase);
    }

    @Override
    @Transactional
    public TestCaseDetailResponse update(UUID id, UpdateTestCaseRequest request) {
        TestCase testCase = findActiveTestCaseOrThrow(id);
        UUID projectId = testCase.getSourceProject().getId();
        projectAccessService.requireCanCreateTestCase(projectId);

        String caseName = normalizeRequiredText(request.getCaseName(), "caseName");

        if (testCaseRepository.existsBySourceProject_IdAndCaseNameIgnoreCaseAndDeletedFlagFalseAndIdNot(
                projectId,
                caseName,
                id
        )) {
            throw new BadRequestException(
                    "Another TestCase already exists with caseName '" + caseName
                            + "' in project id: " + projectId
            );
        }

        ApiEndpoint apiEndpoint = resolveApiEndpointOrNull(request.getApiEndpointId(), projectId);
        ApiDocumentVersion apiDocumentVersion = resolveApiDocumentVersionOrNull(
                request.getApiDocumentVersionId(),
                projectId
        );

        if (request.getInput() == null) {
            throw new BadRequestException("input is required");
        }

        validateUpdateInput(request.getInput());
        validateUpdateAssertions(request.getAssertions());

        testCase.setApiEndpoint(apiEndpoint);
        testCase.setApiDocumentVersion(apiDocumentVersion);
        testCase.setCaseCode(normalizeOptionalText(request.getCaseCode()));
        testCase.setCaseName(caseName);
        testCase.setDescription(normalizeOptionalText(request.getDescription()));
        testCase.setCaseType(request.getCaseType() != null ? request.getCaseType() : CaseType.POSITIVE);
        testCase.setPriorityLevel(request.getPriorityLevel() != null ? request.getPriorityLevel() : PriorityLevel.MEDIUM);
        testCase.setGeneratedBy(request.getGeneratedBy() != null ? request.getGeneratedBy() : GeneratedBy.USER);
        testCase.setActiveFlag(request.getActiveFlag() != null ? request.getActiveFlag() : true);
        testCase.setRequiresWrite(request.getRequiresWrite() != null ? request.getRequiresWrite() : false);
        testCase.setCleanupRequired(request.getCleanupRequired() != null ? request.getCleanupRequired() : false);

        testCase.assignInput(toInputEntity(request.getInput()));
        testCase.replaceAssertions(toUpdatedAssertionEntities(request.getAssertions()));

        TestCase saved = testCaseRepository.save(testCase);
        return toDetailResponse(saved);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        TestCase testCase = findActiveTestCaseOrThrow(id);
        projectAccessService.requireCanDeleteTestCase(testCase.getSourceProject().getId());
        testCase.softDelete();
        testCaseRepository.save(testCase);
    }

    private TestCase findActiveTestCaseOrThrow(UUID id) {
        return testCaseRepository.findByIdAndDeletedFlagFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "TestCase not found with id: " + id
                ));
    }

    private ApiEndpoint resolveApiEndpointOrNull(UUID apiEndpointId, UUID projectId) {
        if (apiEndpointId == null) {
            return null;
        }

        ApiEndpoint apiEndpoint = apiEndpointRepository.findById(apiEndpointId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ApiEndpoint not found with id: " + apiEndpointId
                ));

        UUID endpointProjectId = apiEndpoint.getSourceProject().getId();

        if (!projectId.equals(endpointProjectId)) {
            throw new BadRequestException(
                    "ApiEndpoint id " + apiEndpointId + " does not belong to project id: " + projectId
            );
        }

        return apiEndpoint;
    }

    private ApiDocumentVersion resolveApiDocumentVersionOrNull(UUID apiDocumentVersionId, UUID projectId) {
        if (apiDocumentVersionId == null) {
            return null;
        }

        ApiDocumentVersion version = apiDocumentVersionRepository.findById(apiDocumentVersionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ApiDocumentVersion not found with id: " + apiDocumentVersionId
                ));

        UUID versionProjectId = version.getApiDocument().getSourceProject().getId();

        if (!projectId.equals(versionProjectId)) {
            throw new BadRequestException(
                    "ApiDocumentVersion id " + apiDocumentVersionId
                            + " does not belong to project id: " + projectId
            );
        }

        return version;
    }

    private void validateCreateInput(CreateTestCaseInputRequest input) {
        if (input == null) {
            throw new BadRequestException("input is required");
        }

        validateRequestPath(input.getRequestPath());
        validateJsonObject(input.getQueryParamsJson(), "queryParamsJson");
        validateJsonObject(input.getHeadersJson(), "headersJson");
    }

    private void validateUpdateInput(UpdateTestCaseInputRequest input) {
        if (input == null) {
            throw new BadRequestException("input is required");
        }

        validateRequestPath(input.getRequestPath());
        validateJsonObject(input.getQueryParamsJson(), "queryParamsJson");
        validateJsonObject(input.getHeadersJson(), "headersJson");
    }

    private void validateCreateAssertions(List<CreateTestCaseAssertionRequest> assertions) {
        if (assertions == null || assertions.isEmpty()) {
            throw new BadRequestException("assertions must not be empty");
        }
    }

    private void validateUpdateAssertions(List<UpdateTestCaseAssertionRequest> assertions) {
        if (assertions == null || assertions.isEmpty()) {
            throw new BadRequestException("assertions must not be empty");
        }
    }

    private void validateRequestPath(String requestPath) {
        if (!hasText(requestPath)) {
            throw new BadRequestException("requestPath is required");
        }

        if (!requestPath.trim().startsWith("/")) {
            throw new BadRequestException("requestPath must start with '/'");
        }
    }

    private void validateJsonObject(JsonNode jsonNode, String fieldName) {
        if (jsonNode != null && !jsonNode.isNull() && !jsonNode.isObject()) {
            throw new BadRequestException(fieldName + " must be a JSON object");
        }
    }

    private TestCaseInput toInputEntity(CreateTestCaseInputRequest request) {
        return TestCaseInput.builder()
                .httpMethod(request.getHttpMethod())
                .requestPath(request.getRequestPath().trim())
                .queryParamsJson(toJsonString(request.getQueryParamsJson()))
                .headersJson(toJsonString(request.getHeadersJson()))
                .requestBodyJson(toJsonString(request.getRequestBodyJson()))
                .contentType(normalizeOptionalText(request.getContentType()))
                .timeoutMs(request.getTimeoutMs() != null ? request.getTimeoutMs() : 30000)
                .inputData(normalizeOptionalText(request.getInputData()))
                .build();
    }

    private TestCaseInput toInputEntity(UpdateTestCaseInputRequest request) {
        return TestCaseInput.builder()
                .httpMethod(request.getHttpMethod())
                .requestPath(request.getRequestPath().trim())
                .queryParamsJson(toJsonString(request.getQueryParamsJson()))
                .headersJson(toJsonString(request.getHeadersJson()))
                .requestBodyJson(toJsonString(request.getRequestBodyJson()))
                .contentType(normalizeOptionalText(request.getContentType()))
                .timeoutMs(request.getTimeoutMs() != null ? request.getTimeoutMs() : 30000)
                .inputData(normalizeOptionalText(request.getInputData()))
                .build();
    }

    private List<TestCaseAssertion> toAssertionEntities(List<CreateTestCaseAssertionRequest> requests) {
        return requests.stream()
                .map(request -> TestCaseAssertion.builder()
                        .assertionType(request.getAssertionType())
                        .targetPath(normalizeOptionalText(request.getTargetPath()))
                        .operator(request.getOperator())
                        .expectedValue(normalizeOptionalText(request.getExpectedValue()))
                        .enabledFlag(request.getEnabledFlag() != null ? request.getEnabledFlag() : true)
                        .sortOrder(request.getSortOrder())
                        .build())
                .toList();
    }

    private List<TestCaseAssertion> toUpdatedAssertionEntities(List<UpdateTestCaseAssertionRequest> requests) {
        return requests.stream()
                .map(request -> TestCaseAssertion.builder()
                        .assertionType(request.getAssertionType())
                        .targetPath(normalizeOptionalText(request.getTargetPath()))
                        .operator(request.getOperator())
                        .expectedValue(normalizeOptionalText(request.getExpectedValue()))
                        .enabledFlag(request.getEnabledFlag() != null ? request.getEnabledFlag() : true)
                        .sortOrder(request.getSortOrder())
                        .build())
                .toList();
    }

    private TestCaseResponse toSummaryResponse(TestCase testCase) {
        return TestCaseResponse.builder()
                .id(testCase.getId())
                .projectId(testCase.getSourceProject().getId())
                .apiEndpointId(testCase.getApiEndpoint() == null ? null : testCase.getApiEndpoint().getId())
                .apiDocumentVersionId(testCase.getApiDocumentVersion() == null ? null : testCase.getApiDocumentVersion().getId())
                .caseCode(testCase.getCaseCode())
                .caseName(testCase.getCaseName())
                .description(testCase.getDescription())
                .caseType(testCase.getCaseType())
                .priorityLevel(testCase.getPriorityLevel())
                .generatedBy(testCase.getGeneratedBy())
                .activeFlag(testCase.getActiveFlag())
                .deletedFlag(testCase.getDeletedFlag())
                .requiresWrite(testCase.getRequiresWrite())
                .cleanupRequired(testCase.getCleanupRequired())
                .createdAt(testCase.getCreatedAt())
                .updatedAt(testCase.getUpdatedAt())
                .build();
    }

    private TestCaseDetailResponse toDetailResponse(TestCase testCase) {
        List<TestCaseAssertionResponse> assertionResponses = testCase.getTestCaseAssertions() == null
                ? List.of()
                : testCase.getTestCaseAssertions()
                .stream()
                .sorted(Comparator.comparing(
                        TestCaseAssertion::getSortOrder,
                        Comparator.nullsLast(Integer::compareTo)
                ))
                .map(this::toAssertionResponse)
                .toList();

        return TestCaseDetailResponse.builder()
                .id(testCase.getId())
                .projectId(testCase.getSourceProject().getId())
                .apiEndpointId(testCase.getApiEndpoint() == null ? null : testCase.getApiEndpoint().getId())
                .apiDocumentVersionId(testCase.getApiDocumentVersion() == null ? null : testCase.getApiDocumentVersion().getId())
                .caseCode(testCase.getCaseCode())
                .caseName(testCase.getCaseName())
                .description(testCase.getDescription())
                .caseType(testCase.getCaseType())
                .priorityLevel(testCase.getPriorityLevel())
                .generatedBy(testCase.getGeneratedBy())
                .activeFlag(testCase.getActiveFlag())
                .deletedFlag(testCase.getDeletedFlag())
                .requiresWrite(testCase.getRequiresWrite())
                .cleanupRequired(testCase.getCleanupRequired())
                .input(toInputResponse(testCase.getTestCaseInput()))
                .assertions(assertionResponses)
                .createdAt(testCase.getCreatedAt())
                .updatedAt(testCase.getUpdatedAt())
                .deletedAt(testCase.getDeletedAt())
                .build();
    }

    private TestCaseInputResponse toInputResponse(TestCaseInput input) {
        if (input == null) {
            return null;
        }

        return TestCaseInputResponse.builder()
                .id(input.getId())
                .testCaseId(input.getTestCase() == null ? null : input.getTestCase().getId())
                .httpMethod(input.getHttpMethod())
                .requestPath(input.getRequestPath())
                .queryParamsJson(toJsonNode(input.getQueryParamsJson()))
                .headersJson(toJsonNode(input.getHeadersJson()))
                .requestBodyJson(toJsonNode(input.getRequestBodyJson()))
                .contentType(input.getContentType())
                .timeoutMs(input.getTimeoutMs())
                .inputData(input.getInputData())
                .createdAt(input.getCreatedAt())
                .updatedAt(input.getUpdatedAt())
                .build();
    }

    private TestCaseAssertionResponse toAssertionResponse(TestCaseAssertion assertion) {
        return TestCaseAssertionResponse.builder()
                .id(assertion.getId())
                .testCaseId(assertion.getTestCase() == null ? null : assertion.getTestCase().getId())
                .assertionType(assertion.getAssertionType())
                .targetPath(assertion.getTargetPath())
                .operator(assertion.getOperator())
                .expectedValue(assertion.getExpectedValue())
                .enabledFlag(assertion.getEnabledFlag())
                .sortOrder(assertion.getSortOrder())
                .createdAt(assertion.getCreatedAt())
                .updatedAt(assertion.getUpdatedAt())
                .build();
    }

    private String toJsonString(JsonNode jsonNode) {
        if (jsonNode == null || jsonNode.isNull()) {
            return null;
        }

        try {
            return jsonMapper.writeValueAsString(jsonNode);
        } catch (JacksonException ex) {
            throw new BadRequestException("Invalid JSON value.");
        }
    }

    private JsonNode toJsonNode(String json) {
        if (!hasText(json)) {
            return null;
        }

        try {
            return jsonMapper.readTree(json);
        } catch (JacksonException ex) {
            throw new BadRequestException("Stored JSON value is invalid.");
        }
    }

    private String normalizeRequiredText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new BadRequestException(fieldName + " is required");
        }

        return value.trim();
    }

    private String normalizeOptionalText(String value) {
        if (!hasText(value)) {
            return null;
        }

        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
