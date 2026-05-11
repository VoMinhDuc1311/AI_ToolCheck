package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.rabbitmq.AiTaskMessage;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.CreateTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.GenerateTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.UpdateTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.res.TestCaseDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.res.TestCaseResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseassertion.req.CreateTestCaseAssertionRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseassertion.req.UpdateTestCaseAssertionRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseassertion.res.TestCaseAssertionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.req.CreateTestCaseInputRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.req.UpdateTestCaseInputRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.res.TestCaseInputResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.AssertionType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.CaseType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.GeneratedBy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.PriorityLevel;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiDocumentVersion;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCase;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseAssertion;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiJobLogRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiDocumentVersionRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestCaseRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJsonParserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiModelRouterService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestCaseService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.ai.AiPromptConstants;
import com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq.AiTaskProducer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestCaseServiceImpl implements TestCaseService {

    private final TestCaseRepository testCaseRepository;
    private final ApiEndpointRepository apiEndpointRepository;
    private final ApiDocumentVersionRepository apiDocumentVersionRepository;
    private final AiJobLogRepository aiJobLogRepository;
    private final AiTaskProducer aiTaskProducer;
    private final AiModelRouterService aiModelRouterService;
    private final AiJsonParserService aiJsonParserService;
    private final ProjectAccessService projectAccessService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public TestCaseDetailResponse create(CreateTestCaseRequest request) {
        SourceProject sourceProject = projectAccessService.requireCanCreateTestCase(request.getProjectId());

        String caseName = normalizeRequiredText(request.getCaseName(), "caseName");

        if (testCaseRepository.existsBySourceProject_IdAndCaseNameIgnoreCaseAndDeletedFlagFalse(
                request.getProjectId(),
                caseName)) {
            throw new BadRequestException(
                    "TestCase already exists with caseName '" + caseName
                            + "' in project id: " + request.getProjectId());
        }

        ApiEndpoint apiEndpoint = resolveApiEndpointOrNull(request.getApiEndpointId(), sourceProject.getId());
        ApiDocumentVersion apiDocumentVersion = resolveApiDocumentVersionOrNull(
                request.getApiDocumentVersionId(),
                sourceProject.getId());

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
                id)) {
            throw new BadRequestException(
                    "Another TestCase already exists with caseName '" + caseName
                            + "' in project id: " + projectId);
        }

        ApiEndpoint apiEndpoint = resolveApiEndpointOrNull(request.getApiEndpointId(), projectId);
        ApiDocumentVersion apiDocumentVersion = resolveApiDocumentVersionOrNull(
                request.getApiDocumentVersionId(),
                projectId);

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
        testCase.setPriorityLevel(
                request.getPriorityLevel() != null ? request.getPriorityLevel() : PriorityLevel.MEDIUM);
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

    @Override
    @Transactional
    public UUID generateTestCaseAsync(GenerateTestCaseRequest request) {
        // Merge check: ensure project access security is enforced
        SourceProject sourceProject = projectAccessService.requireCanCreateTestCase(request.getProjectId());

        ApiEndpoint apiEndpoint = resolveApiEndpointOrNull(request.getApiEndpointId(), sourceProject.getId());
        if (apiEndpoint == null) {
            throw new BadRequestException("ApiEndpoint is required for generating test cases");
        }

        AiJobLog jobLog = AiJobLog.builder()
                .jobType(JobType.TEST_CASE_GENERATION)
                .executionStatus(ExecutionStatus.PENDING)
                .sourceProject(sourceProject)
                .apiEndpoint(apiEndpoint)
                .startedAt(LocalDateTime.now())
                .build();

        AiJobLog savedJob = aiJobLogRepository.save(jobLog);

        AiTaskMessage message = AiTaskMessage.builder()
                .jobId(savedJob.getId().toString())
                .projectId(sourceProject.getId().toString())
                .apiEndpointId(apiEndpoint.getId().toString())
                .skillCode("GENERATE_TEST_CASE")
                .build();

        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        aiTaskProducer.sendAiTask(message);
                        log.info("Đã đẩy AiTaskMessage vào RabbitMQ cho Job ID: [{}]", savedJob.getId());
                    }
                });

        return savedJob.getId();
    }

    @Override
    @Transactional
    public String generateTestCaseProcessing(String endpointId, UUID jobId) {
        if (endpointId == null || endpointId.trim().isEmpty()) {
            throw new BadRequestException("ApiEndpoint ID is required for generating test cases.");
        }

        ApiEndpoint endpoint = apiEndpointRepository.findById(UUID.fromString(endpointId))
                .orElseThrow(() -> new ResourceNotFoundException("ApiEndpoint not found: " + endpointId));

        // 1. Tổng hợp thông tin API (Method, Path, Parameters)
        String method = endpoint.getHttpMethod() != null ? endpoint.getHttpMethod().name() : "GET";
        String path = endpoint.getEndpointPath() != null ? endpoint.getEndpointPath() : "/";

        StringBuilder apiDetails = new StringBuilder();
        apiDetails.append("Method: ").append(method).append("\n");
        apiDetails.append("Path: ").append(path).append("\n");

        if (endpoint.getApiParameters() != null && !endpoint.getApiParameters().isEmpty()) {
            apiDetails.append("Parameters:\n");
            endpoint.getApiParameters().forEach(p -> apiDetails.append("- ").append(p.getParamName())
                    .append(" (").append(p.getParamIn()).append("): ")
                    .append(p.getDataType()).append("\n"));
        }

        // 2. Lấy Schema Definitions
        StringBuilder schemas = new StringBuilder();
        if (endpoint.getEndpointSchemaMaps() != null) {
            endpoint.getEndpointSchemaMaps().forEach(map -> {
                if (map.getApiSchema() != null) {
                    schemas.append("Schema [").append(map.getUsageType()).append("]: ")
                            .append(map.getApiSchema().getSchemaName()).append("\n");
                }
            });
        }

        // 3. Inject Context vào Prompt
        String prompt = String.format(
                AiPromptConstants.PROMPT_SKILL_2_GEN_TESTCASE,
                apiDetails.toString(),
                schemas.toString());

        // 4. Định tuyến AI và thực thi
        String rawResult = aiModelRouterService.routeAndExecute(prompt, jobId);

        // 5. Sau khi nhận kết quả, persist ngay vào DB
        try {
            persistTestCasesFromAi(rawResult, UUID.fromString(endpointId));
        } catch (Exception e) {
            log.warn(
                    "[TestCaseService] Persist test case thất bại nhưng vẫn trả về rawJson để Consumer không crash: {}",
                    e.getMessage());
        }

        return rawResult;
    }

    @Override
    @Transactional
    public void persistTestCasesFromAi(String rawJson, UUID endpointId) {
        // 1. Self-Healing: parse JSON thô từ AI thành List<AiTestCaseDto>
        List<com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiTestCaseDto> dtos = aiJsonParserService
                .cleanAndParseTestCaseJson(rawJson);

        if (dtos.isEmpty()) {
            log.warn("[TestCaseService] AI không sinh được test case nào. Bỏ qua lưu DB.");
            return;
        }

        // Lấy tham chiếu ApiEndpoint
        ApiEndpoint apiEndpoint = apiEndpointRepository.findById(endpointId)
                .orElseThrow(() -> new ResourceNotFoundException("ApiEndpoint not found: " + endpointId));

        // Lấy tham chiếu SourceProject từ endpoint
        SourceProject sourceProject = apiEndpoint.getSourceProject();

        log.info("[TestCaseService] Bắt đầu persist {} test case(s) cho endpoint: {}", dtos.size(), endpointId);

        // 2. Xây dựng danh sách TestCase entity
        List<TestCase> testCasesToSave = new java.util.ArrayList<>();

        for (com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiTestCaseDto dto : dtos) {

            // Fallback case_name nếu AI không sinh tên
            String caseName = (dto.getCaseName() != null && !dto.getCaseName().isBlank())
                    ? dto.getCaseName().trim()
                    : "AI_GENERATED_" + System.currentTimeMillis();

            // Normalize và map CaseType (phòng thủ: fallback về POSITIVE nếu sai)
            CaseType caseType = CaseType.POSITIVE;
            if (dto.getCaseType() != null) {
                try {
                    String normalized = dto.getCaseType().trim().toUpperCase();
                    // Map từ giá trị AI sang Enum dự án
                    caseType = switch (normalized) {
                        case "SUCCESS" -> CaseType.POSITIVE;
                        case "VALIDATION_ERROR", "CLIENT_ERROR" -> CaseType.VALIDATION;
                        case "UNAUTHORIZED" -> CaseType.AUTHORIZATION;
                        case "SERVER_ERROR" -> CaseType.NEGATIVE;
                        default -> CaseType.valueOf(normalized); // thử parse trực tiếp
                    };
                } catch (IllegalArgumentException e) {
                    log.warn("[TestCaseService] CaseType không hợp lệ '{}' — fallback về POSITIVE.", dto.getCaseType());
                }
            }

            // Normalize và map PriorityLevel
            com.aitoolcheck.ai_toolcheck1_backend.enums.PriorityLevel priorityLevel = com.aitoolcheck.ai_toolcheck1_backend.enums.PriorityLevel.MEDIUM;
            if (dto.getPriorityLevel() != null) {
                try {
                    priorityLevel = com.aitoolcheck.ai_toolcheck1_backend.enums.PriorityLevel
                            .valueOf(dto.getPriorityLevel().trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    log.warn("[TestCaseService] PriorityLevel không hợp lệ '{}' — fallback về MEDIUM.",
                            dto.getPriorityLevel());
                }
            }

            // Xây dựng TestCaseInput từ dữ liệu AI (Path, Query, Body)
            String requestPath = apiEndpoint.getEndpointPath() != null ? apiEndpoint.getEndpointPath() : "/";
            String queryParamsJson = null;
            String requestBodyJson = null;

            // 1. Thay thế Path Variables nếu có (ví dụ: {id} -> 123)
            if (dto.getPathParams() != null && dto.getPathParams().isObject()) {
                java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = dto.getPathParams().fields();
                while (fields.hasNext()) {
                    java.util.Map.Entry<String, JsonNode> field = fields.next();
                    String placeholder = "{" + field.getKey() + "}";
                    String value = field.getValue().asText();
                    requestPath = requestPath.replace(placeholder, value);
                }
            }

            // 2. Serialize Query Params
            if (dto.getQueryParams() != null && !dto.getQueryParams().isNull()) {
                try {
                    queryParamsJson = objectMapper.writeValueAsString(dto.getQueryParams());
                } catch (Exception e) {
                    log.warn("[TestCaseService] Không thể serialize query_params — bỏ qua.");
                }
            }

            // 3. Serialize Request Body
            if (dto.getRequestBody() != null && !dto.getRequestBody().isNull()) {
                try {
                    requestBodyJson = objectMapper.writeValueAsString(dto.getRequestBody());
                } catch (Exception e) {
                    log.warn("[TestCaseService] Không thể serialize request_body — bỏ qua.");
                }
            }

            // Fallback inputData cho Audit
            String inputDataJson = null;
            if (dto.getInputData() != null && !dto.getInputData().isNull()) {
                try {
                    inputDataJson = objectMapper.writeValueAsString(dto.getInputData());
                } catch (Exception e) {
                    log.warn("[TestCaseService] Không thể serialize inputData — bỏ qua.");
                }
            }

            TestCaseInput input = TestCaseInput.builder()
                    .httpMethod(apiEndpoint.getHttpMethod())
                    .requestPath(requestPath)
                    .queryParamsJson(queryParamsJson)
                    .requestBodyJson(requestBodyJson)
                    .inputData(inputDataJson)
                    .build();

            // Xây dựng danh sách TestCaseAssertion
            List<TestCaseAssertion> assertions = new java.util.ArrayList<>();
            int sortOrder = 1;
            for (com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiAssertionDto aDto : dto.getAssertions()) {

                // Normalize AssertionType
                AssertionType assertionType = AssertionType.STATUS_CODE;
                if (aDto.getAssertionType() != null) {
                    try {
                        String norm = aDto.getAssertionType().trim().toUpperCase();
                        assertionType = switch (norm) {
                            case "JSON_BODY" -> AssertionType.JSON_PATH;
                            default -> AssertionType.valueOf(norm);
                        };
                    } catch (IllegalArgumentException e) {
                        log.warn("[TestCaseService] AssertionType không hợp lệ '{}' — fallback về STATUS_CODE.",
                                aDto.getAssertionType());
                    }
                }

                // Normalize ComparisonOperator
                com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator operator = com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator.EQUALS;
                if (aDto.getOperator() != null) {
                    try {
                        String norm = aDto.getOperator().trim().toUpperCase();
                        operator = switch (norm) {
                            case "NOT_NULL" ->
                                com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator.IS_NOT_NULL;
                            default -> com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator.valueOf(norm);
                        };
                    } catch (IllegalArgumentException e) {
                        log.warn("[TestCaseService] Operator không hợp lệ '{}' — fallback về EQUALS.",
                                aDto.getOperator());
                    }
                }

                assertions.add(TestCaseAssertion.builder()
                        .assertionType(assertionType)
                        .targetPath(aDto.getTargetPath())
                        .operator(operator)
                        .expectedValue(aDto.getExpectedValue())
                        .sortOrder(sortOrder++)
                        .build());
            }

            // Xây dựng TestCase entity (Dùng constructor/setter thay vì builder để đảm bảo Collections hoạt động chuẩn với JPA)
            TestCase testCase = new TestCase();
            testCase.setCaseName(caseName);
            testCase.setCaseType(caseType);
            testCase.setPriorityLevel(priorityLevel);
            testCase.setGeneratedBy(com.aitoolcheck.ai_toolcheck1_backend.enums.GeneratedBy.AI);
            testCase.setActiveFlag(true);
            testCase.setDeletedFlag(false);
            testCase.setRequiresWrite(false);
            testCase.setCleanupRequired(false);
            testCase.setSourceProject(sourceProject);
            testCase.setApiEndpoint(apiEndpoint);

            // Assign các quan hệ Cascade
            testCase.assignInput(input);
            testCase.replaceAssertions(assertions);
            
            testCasesToSave.add(testCase);
        }

        // 3. Batch save — tối ưu DB round-trip
        testCaseRepository.saveAll(testCasesToSave);
        log.info("[TestCaseService] Đã lưu thành công {} test case(s) cho endpoint: {}", testCasesToSave.size(),
                endpointId);
    }

    private TestCase findActiveTestCaseOrThrow(UUID id) {
        return testCaseRepository.findByIdAndDeletedFlagFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "TestCase not found with id: " + id));
    }

    private ApiEndpoint resolveApiEndpointOrNull(UUID apiEndpointId, UUID projectId) {
        if (apiEndpointId == null) {
            return null;
        }

        ApiEndpoint apiEndpoint = apiEndpointRepository.findById(apiEndpointId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ApiEndpoint not found with id: " + apiEndpointId));

        UUID endpointProjectId = apiEndpoint.getSourceProject().getId();

        if (!projectId.equals(endpointProjectId)) {
            throw new BadRequestException(
                    "ApiEndpoint id " + apiEndpointId + " does not belong to project id: " + projectId);
        }

        return apiEndpoint;
    }

    private ApiDocumentVersion resolveApiDocumentVersionOrNull(UUID apiDocumentVersionId, UUID projectId) {
        if (apiDocumentVersionId == null) {
            return null;
        }

        ApiDocumentVersion version = apiDocumentVersionRepository.findById(apiDocumentVersionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ApiDocumentVersion not found with id: " + apiDocumentVersionId));

        UUID versionProjectId = version.getApiDocument().getSourceProject().getId();

        if (!projectId.equals(versionProjectId)) {
            throw new BadRequestException(
                    "ApiDocumentVersion id " + apiDocumentVersionId
                            + " does not belong to project id: " + projectId);
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
                .apiDocumentVersionId(
                        testCase.getApiDocumentVersion() == null ? null : testCase.getApiDocumentVersion().getId())
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
                                Comparator.nullsLast(Integer::compareTo)))
                        .map(this::toAssertionResponse)
                        .toList();

        return TestCaseDetailResponse.builder()
                .id(testCase.getId())
                .projectId(testCase.getSourceProject().getId())
                .apiEndpointId(testCase.getApiEndpoint() == null ? null : testCase.getApiEndpoint().getId())
                .apiDocumentVersionId(
                        testCase.getApiDocumentVersion() == null ? null : testCase.getApiDocumentVersion().getId())
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
            return objectMapper.writeValueAsString(jsonNode);
        } catch (Exception ex) {
            throw new BadRequestException("Invalid JSON value.");
        }
    }

    private JsonNode toJsonNode(String json) {
        if (!hasText(json)) {
            return null;
        }

        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
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