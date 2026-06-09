package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.rabbitmq.AiTaskMessage;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiGeneratedTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseAssertionDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseInputDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.AiTestCaseItemDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.CreateTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.GenerateTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.UpdateTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.res.TestCaseDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.res.TestCaseResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.res.ApiEndpointResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseassertion.req.CreateTestCaseAssertionRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseassertion.req.UpdateTestCaseAssertionRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseassertion.res.TestCaseAssertionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.req.CreateTestCaseInputRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.req.UpdateTestCaseInputRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcaseinput.res.TestCaseInputResponse;
import com.aitoolcheck.ai_toolcheck1_backend.config.properties.GeminiProperties;
import com.aitoolcheck.ai_toolcheck1_backend.enums.*;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiPersistenceException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.*;
import com.aitoolcheck.ai_toolcheck1_backend.repository.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJsonParserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.GeminiApiClientService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiModelRouterService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestCaseService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.ai.AiTestCaseAssertionSanitizer;
import com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq.AiTaskProducer;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiPayloadOptimizerService;
import com.aitoolcheck.ai_toolcheck1_backend.config.properties.AiOptimizationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Join;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.PagedResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestCaseServiceImpl implements TestCaseService {

    private final TestCaseRepository testCaseRepository;
    private final TestCaseAssertionRepository testCaseAssertionRepository;
    private final ApiEndpointRepository apiEndpointRepository;
    private final ApiDocumentVersionRepository apiDocumentVersionRepository;
    private final AiJobLogRepository aiJobLogRepository;
    private final AiTaskProducer aiTaskProducer;
    private final AiModelRouterService aiModelRouterService;
    private final GeminiApiClientService geminiApiClientService;
    private final AiJsonParserService aiJsonParserService;
    private final ProjectAccessService projectAccessService;
    private final AiSkillRepository aiSkillRepository;
    private final ObjectMapper objectMapper;
    private final org.springframework.context.ApplicationContext applicationContext;
    private final AiPayloadOptimizerService aiPayloadOptimizerService;
    private final AiOptimizationProperties aiOptimizationProperties;
    private final AiTestCaseAssertionSanitizer aiTestCaseAssertionSanitizer;
    private final GeminiProperties geminiProperties;

    private static final String AGENT_2_COMPACT_PROMPT_TEMPLATE = """
            Return JSON only. No markdown. No explanation.

            Context:
            {{OPENAPI_OPERATION_CONTEXT}}

            Rules:
            - Generate at most 1 positive smoke testcase for GET, HEAD, or OPTIONS.
            - Use STATUS_CODE EQUALS first 2xx response code; default 200.
            - Do not assert exact whole response body.
            - Do not expect {} or [] unless explicit OpenAPI example says so.
            - Do not assert fixed dynamic fields: id, uuid, createdAt, updatedAt, timestamp, token.
            - If context says no stable response example is available, generate status-code-only smoke test.
            - Use only assertion_type STATUS_CODE, JSON_PATH, HEADER, RESPONSE_TIME.
            - Use only comparison_operator EQUALS, NOT_EQUALS, CONTAINS, NOT_NULL, IS_NULL, EXISTS.

            Output shape:
            {
              "test_cases": [
                {
                  "test_name": "Smoke: endpoint returns success",
                  "case_type": "SUCCESS",
                  "priority": "MEDIUM",
                  "http_method": "GET",
                  "url": "/api/path",
                  "inputs": [],
                  "assertions": [
                    {
                      "assertion_type": "STATUS_CODE",
                      "json_path": "",
                      "comparison_operator": "EQUALS",
                      "expected_value": "200"
                    }
                  ]
                }
              ]
            }
            """;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveAiGeneratedTestCases(AiGeneratedTestCaseRequest request, UUID apiEndpointId, UUID aiJobId) {
        log.info("[Phase 3] Bắt đầu lưu trữ {} AI test cases cho Endpoint: {}, Job: {}",
                request.getTestCases().size(), apiEndpointId, aiJobId);

        try {
            ApiEndpoint endpoint = apiEndpointRepository.findById(apiEndpointId)
                    .orElseThrow(() -> new ResourceNotFoundException("ApiEndpoint không tồn tại: " + apiEndpointId));

            SourceProject project = endpoint.getSourceProject();

            for (AiTestCaseItemDto itemDto : request.getTestCases()) {
                sanitizeTestCaseDto(itemDto, endpoint.getHttpMethod());

                // 1. Map CaseType (Defensive)
                CaseType caseType = CaseType.POSITIVE;
                if (itemDto.getCaseType() != null) {
                    try {
                        String norm = itemDto.getCaseType().trim().toUpperCase();
                        caseType = switch (norm) {
                            case "SUCCESS" -> CaseType.POSITIVE;
                            case "VALIDATION_ERROR", "CLIENT_ERROR" -> CaseType.VALIDATION;
                            case "UNAUTHORIZED" -> CaseType.AUTHORIZATION;
                            case "SERVER_ERROR" -> CaseType.NEGATIVE;
                            default -> CaseType.valueOf(norm);
                        };
                    } catch (Exception e) {
                        log.warn("[TestCaseService] CaseType không hợp lệ '{}' - fallback POSITIVE",
                                itemDto.getCaseType());
                    }
                }

                // 2. Map PriorityLevel (Defensive)
                PriorityLevel priorityLevel = PriorityLevel.MEDIUM;
                if (itemDto.getPriority() != null) {
                    try {
                        priorityLevel = PriorityLevel.valueOf(itemDto.getPriority().trim().toUpperCase());
                    } catch (Exception e) {
                        log.warn("[TestCaseService] Priority không hợp lệ '{}' - fallback MEDIUM",
                                itemDto.getPriority());
                    }
                }

                HttpMethod method = itemDto.getHttpMethod();
                boolean requiresWrite = method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH || method == HttpMethod.DELETE;
                boolean cleanupRequired = requiresWrite && (caseType == CaseType.POSITIVE);

                boolean activeFlag = true;
                if (method == HttpMethod.GET && caseType == CaseType.POSITIVE && itemDto.getUrl() != null) {
                    String upperUrl = itemDto.getUrl().toUpperCase();
                    if (upperUrl.contains("CUSTOMER_ABC_123") ||
                        upperUrl.contains("UNKNOWN_") ||
                        upperUrl.contains("SAMPLE_") ||
                        upperUrl.contains("TEST_") ||
                        upperUrl.contains("FAKE_") ||
                        upperUrl.contains("DUMMY_")) {
                        activeFlag = false;
                    }
                }

                // 3. Tạo Entity TestCase (Bảng Cha)
                TestCase testCase = TestCase.builder()
                        .caseName(itemDto.getTestName())
                        .caseType(caseType)
                        .priorityLevel(priorityLevel)
                        .generatedBy(GeneratedBy.AI)
                        .activeFlag(activeFlag)
                        .deletedFlag(false)
                        .requiresWrite(requiresWrite)
                        .cleanupRequired(cleanupRequired)
                        .sourceProject(project)
                        .apiEndpoint(endpoint)
                        .build();

                // 4. Xử lý TestCaseInput (Quan hệ 1-1)
                TestCaseInput inputEntity = TestCaseInput.builder()
                        .httpMethod(itemDto.getHttpMethod())
                        .requestPath(itemDto.getUrl())
                        .build();

                if (itemDto.getInputs() != null) {
                    for (AiTestCaseInputDto inputDto : itemDto.getInputs()) {
                        if (inputDto.getParamIn() == null)
                            continue;

                        switch (inputDto.getParamIn()) {
                            case QUERY -> inputEntity.setQueryParamsJson(toJsonString(inputDto.getPayload()));
                            case BODY -> inputEntity.setRequestBodyJson(toJsonString(inputDto.getPayload()));
                            case HEADER -> inputEntity.setHeadersJson(toJsonString(inputDto.getPayload()));
                            default -> log.debug("Bỏ qua input type: {}", inputDto.getParamIn());
                        }
                    }
                }

                testCase.assignInput(inputEntity);

                // 5. Xử lý TestCaseAssertion (Quan hệ 1-N)
                List<TestCaseAssertion> assertionEntities = new ArrayList<>();
                if (itemDto.getAssertions() != null && !itemDto.getAssertions().isEmpty()) {
                    int sortOrder = 1;
                    for (AiTestCaseAssertionDto assertionDto : itemDto.getAssertions()) {

                        // Map AssertionType (Defensive)
                        AssertionType assertionType = AssertionType.STATUS_CODE;
                        if (assertionDto.getAssertionType() != null) {
                            try {
                                String norm = assertionDto.getAssertionType().trim().toUpperCase();
                                assertionType = switch (norm) {
                                    case "JSON_BODY", "BODY", "RESPONSE_BODY" -> AssertionType.JSON_PATH;
                                    default -> AssertionType.valueOf(norm);
                                };
                            } catch (Exception e) {
                                log.warn("[TestCaseService] AssertionType không hợp lệ '{}'",
                                        assertionDto.getAssertionType());
                            }
                        }

                        // Map ComparisonOperator (Defensive)
                        ComparisonOperator operator = ComparisonOperator.EQUALS;
                        if (assertionDto.getComparisonOperator() != null) {
                            try {
                                String norm = assertionDto.getComparisonOperator().trim().toUpperCase();
                                operator = switch (norm) {
                                    case "NOT_NULL" -> ComparisonOperator.IS_NOT_NULL;
                                    case "IS_NULL" -> ComparisonOperator.IS_NULL;
                                    default -> ComparisonOperator.valueOf(norm);
                                };
                            } catch (Exception e) {
                                log.warn("[TestCaseService] Operator không hợp lệ '{}'",
                                        assertionDto.getComparisonOperator());
                            }
                        }

                        assertionEntities.add(TestCaseAssertion.builder()
                                .testCase(testCase)
                                .assertionType(assertionType)
                                .targetPath(assertionDto.getJsonPath())
                                .operator(operator)
                                .expectedValue(assertionDto.getExpectedValue())
                                .enabledFlag(true)
                                .sortOrder(sortOrder++)
                                .build());
                    }
                }

                assertionEntities = aiTestCaseAssertionSanitizer.sanitize(
                        assertionEntities,
                        itemDto.getHttpMethod(),
                        caseType,
                        itemDto.getExpectedStatusCode(),
                        false);

                testCase.replaceAssertions(assertionEntities);

                // Lưu TestCase (Kéo theo Input và Assertions nhờ CascadeType.ALL)
                testCaseRepository.save(testCase);
            }

            log.info("[Phase 3] Hoàn thành lưu trữ AI test cases cho Job: {}", aiJobId);

        } catch (DataAccessException e) {
            log.error("[Phase 3] Lỗi truy vấn Database khi lưu AI Test Cases: {}", e.getMessage());
            // CHỈ NÉM LỖI: Để tầng caller xử lý FAILED status (tránh bị Rollback nuốt mất
            // update FAILED)
            throw new AiPersistenceException("Lỗi lưu trữ dữ liệu AI Test Case xuống Database: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("[Phase 3] Lỗi không xác định khi lưu AI Test Cases: {}", e.getMessage());
            throw new AiPersistenceException("Lỗi hệ thống khi thực hiện Phase 3 (Persistence): " + e.getMessage(), e);
        }
    }

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
    public PagedResponse<TestCaseDetailResponse> searchTestCases(
            UUID projectId,
            String keyword,
            String caseType,
            String priorityLevel,
            String generatedBy,
            Boolean activeFlag,
            Boolean requiresWrite,
            Boolean cleanupRequired,
            String httpMethod,
            String endpointPath,
            UUID apiEndpointId,
            String createdFrom,
            String createdTo,
            String updatedFrom,
            String updatedTo,
            int page,
            int size,
            String sortBy,
            String sortDir
    ) {
        projectAccessService.requireCanViewProject(projectId);

        // Build sorting
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        if (sortBy != null && !sortBy.trim().isEmpty()) {
            Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
            String cleanSortBy = sortBy.trim();
            if ("endpoint".equalsIgnoreCase(cleanSortBy)) {
                sort = Sort.by(direction, "apiEndpoint.endpointPath");
            } else if ("name".equalsIgnoreCase(cleanSortBy) || "caseName".equalsIgnoreCase(cleanSortBy)) {
                sort = Sort.by(direction, "caseName");
            } else if ("priority".equalsIgnoreCase(cleanSortBy) || "priorityLevel".equalsIgnoreCase(cleanSortBy)) {
                sort = Sort.by(direction, "priorityLevel");
            } else {
                sort = Sort.by(direction, cleanSortBy);
            }
        }

        Pageable pageable = PageRequest.of(page, size, sort);

        Specification<TestCase> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("sourceProject").get("id"), projectId));
            predicates.add(cb.equal(root.get("deletedFlag"), false));

            if (keyword != null && !keyword.trim().isEmpty()) {
                String kw = "%" + keyword.trim().toLowerCase() + "%";
                Join<Object, Object> inputJoin = root.join("testCaseInput", JoinType.LEFT);
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("caseName")), kw),
                    cb.like(cb.lower(root.get("description")), kw),
                    cb.like(cb.lower(root.get("caseCode")), kw),
                    cb.like(cb.lower(inputJoin.get("requestPath")), kw)
                ));
            }

            if (caseType != null && !caseType.trim().isEmpty() && !"ALL".equalsIgnoreCase(caseType)) {
                try {
                    predicates.add(cb.equal(root.get("caseType"), CaseType.valueOf(caseType.trim().toUpperCase())));
                } catch (Exception ignored) {}
            }
            if (priorityLevel != null && !priorityLevel.trim().isEmpty() && !"ALL".equalsIgnoreCase(priorityLevel)) {
                try {
                    predicates.add(cb.equal(root.get("priorityLevel"), PriorityLevel.valueOf(priorityLevel.trim().toUpperCase())));
                } catch (Exception ignored) {}
            }
            if (generatedBy != null && !generatedBy.trim().isEmpty() && !"ALL".equalsIgnoreCase(generatedBy)) {
                try {
                    predicates.add(cb.equal(root.get("generatedBy"), GeneratedBy.valueOf(generatedBy.trim().toUpperCase())));
                } catch (Exception ignored) {}
            }

            if (activeFlag != null) {
                predicates.add(cb.equal(root.get("activeFlag"), activeFlag));
            }
            if (requiresWrite != null) {
                predicates.add(cb.equal(root.get("requiresWrite"), requiresWrite));
            }
            if (cleanupRequired != null) {
                predicates.add(cb.equal(root.get("cleanupRequired"), cleanupRequired));
            }

            if (httpMethod != null && !httpMethod.trim().isEmpty() && !"ALL".equalsIgnoreCase(httpMethod)) {
                Join<Object, Object> inputJoin = root.join("testCaseInput", JoinType.LEFT);
                try {
                    predicates.add(cb.equal(inputJoin.get("httpMethod"), HttpMethod.valueOf(httpMethod.trim().toUpperCase())));
                } catch (Exception ignored) {}
            }

            if (endpointPath != null && !endpointPath.trim().isEmpty() && !"ALL".equalsIgnoreCase(endpointPath)) {
                Join<Object, Object> endpointJoin = root.join("apiEndpoint", JoinType.LEFT);
                predicates.add(cb.like(cb.lower(endpointJoin.get("endpointPath")), "%" + endpointPath.trim().toLowerCase() + "%"));
            }

            if (apiEndpointId != null) {
                predicates.add(cb.equal(root.get("apiEndpoint").get("id"), apiEndpointId));
            }

            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            if (createdFrom != null && !createdFrom.trim().isEmpty()) {
                try {
                    LocalDateTime start = LocalDate.parse(createdFrom.trim(), dateFormatter).atStartOfDay();
                    predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
                } catch (Exception ignored) {}
            }
            if (createdTo != null && !createdTo.trim().isEmpty()) {
                try {
                    LocalDateTime end = LocalDate.parse(createdTo.trim(), dateFormatter).atTime(23, 59, 59);
                    predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), end));
                } catch (Exception ignored) {}
            }
            if (updatedFrom != null && !updatedFrom.trim().isEmpty()) {
                try {
                    LocalDateTime start = LocalDate.parse(updatedFrom.trim(), dateFormatter).atStartOfDay();
                    predicates.add(cb.greaterThanOrEqualTo(root.get("updatedAt"), start));
                } catch (Exception ignored) {}
            }
            if (updatedTo != null && !updatedTo.trim().isEmpty()) {
                try {
                    LocalDateTime end = LocalDate.parse(updatedTo.trim(), dateFormatter).atTime(23, 59, 59);
                    predicates.add(cb.lessThanOrEqualTo(root.get("updatedAt"), end));
                } catch (Exception ignored) {}
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<TestCase> pageResult = testCaseRepository.findAll(spec, pageable);

        List<TestCase> testCases = pageResult.getContent();
        if (!testCases.isEmpty()) {
            List<UUID> testCaseIds = testCases.stream().map(TestCase::getId).toList();
            testCaseAssertionRepository.findByTestCase_IdIn(testCaseIds);
        }

        List<TestCaseDetailResponse> itemResponses = testCases.stream()
                .map(this::toDetailResponse)
                .toList();

        return PagedResponse.<TestCaseDetailResponse>builder()
                .items(itemResponses)
                .page(pageResult.getNumber())
                .size(pageResult.getSize())
                .totalElements(pageResult.getTotalElements())
                .totalPages(pageResult.getTotalPages())
                .hasNext(pageResult.hasNext())
                .hasPrevious(pageResult.hasPrevious())
                .build();
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

        if (request.getApiEndpointId() == null) {
            List<ApiEndpoint> endpoints = apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(sourceProject.getId());
            if (endpoints.isEmpty()) {
                throw new BadRequestException("Không tìm thấy API Endpoint hoạt động nào trong dự án để sinh test case.");
            }

            AiSkill aiSkill = aiSkillRepository.findBySkillCode("generate_testcases").orElse(null);
            UUID firstJobId = null;

            for (ApiEndpoint apiEndpoint : endpoints) {
                AiJobLog jobLog = AiJobLog.builder()
                        .jobType(JobType.TEST_CASE_GENERATION)
                        .executionStatus(ExecutionStatus.PENDING)
                        .sourceProject(sourceProject)
                        .apiEndpoint(apiEndpoint)
                        .aiSkill(aiSkill)
                        .startedAt(LocalDateTime.now())
                        .build();

                AiJobLog savedJob = aiJobLogRepository.save(jobLog);
                if (firstJobId == null) {
                    firstJobId = savedJob.getId();
                }

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
                                log.info("Đã đẩy AiTaskMessage vào RabbitMQ cho Job ID: [{}] (Project-wide)", savedJob.getId());
                            }
                        });
            }

            return firstJobId != null ? firstJobId : UUID.randomUUID();
        }

        ApiEndpoint apiEndpoint = resolveApiEndpointOrNull(request.getApiEndpointId(), sourceProject.getId());
        if (apiEndpoint == null) {
            throw new BadRequestException("ApiEndpoint is required for generating test cases");
        }

        AiSkill aiSkill = aiSkillRepository.findBySkillCode("generate_testcases").orElse(null);

        AiJobLog jobLog = AiJobLog.builder()
                .jobType(JobType.TEST_CASE_GENERATION)
                .executionStatus(ExecutionStatus.PENDING)
                .sourceProject(sourceProject)
                .apiEndpoint(apiEndpoint)
                .aiSkill(aiSkill)
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
    public String generateTestCaseProcessing(String endpointId, UUID jobId) {
        if (endpointId == null || endpointId.trim().isEmpty()) {
            throw new BadRequestException("ApiEndpoint ID is required for generating test cases.");
        }

        // Load endpoint with lazy collections initialised inside a transaction
        TestCaseService proxySelf = applicationContext.getBean(TestCaseService.class);
        ApiEndpoint endpoint = proxySelf.getEndpointWithDetails(UUID.fromString(endpointId));

        UUID projectId = endpoint.getSourceProject().getId();
        String httpMethod = endpoint.getHttpMethod() != null ? endpoint.getHttpMethod().name() : "GET";
        String endpointPath = endpoint.getEndpointPath() != null ? endpoint.getEndpointPath() : "/";

        // 1. Load latest ApiDocumentVersion for this project (Agent 1 output)
        ApiDocumentVersion latestVersion = resolveLatestOpenApiVersion(projectId);
        if (latestVersion == null || latestVersion.getContentJson() == null
                || latestVersion.getContentJson().isBlank()) {
            String msg = "No OpenAPI document found for this project. " +
                    "Please run Agent 1 Generate OpenAPI first. projectId=" + projectId;
            log.warn("[GenerateTestCase] {} endpointId={} jobId={}", msg, endpointId, jobId);
            aiJobLogRepository.findById(jobId).ifPresent(job -> {
                job.setExecutionStatus(ExecutionStatus.FAILED);
                job.setCompletedAt(LocalDateTime.now());
                job.setErrorMessage(msg);
                aiJobLogRepository.save(job);
            });
            throw new BadRequestException(msg);
        }

        // 2. Build compact OpenAPI context from Agent 1 document
        String compactContext;
        try {
            compactContext = buildCompactOpenApiContext(endpoint, latestVersion);
        } catch (BadRequestException e) {
            // Operation not found or document unreadable — fail the job clearly
            aiJobLogRepository.findById(jobId).ifPresent(job -> {
                job.setExecutionStatus(ExecutionStatus.FAILED);
                job.setCompletedAt(LocalDateTime.now());
                job.setErrorMessage(e.getMessage());
                aiJobLogRepository.save(job);
            });
            throw e;
        } catch (Exception e) {
            String msg = "Failed to parse OpenAPI document for context building: " + e.getMessage();
            log.error("[GenerateTestCase] {} endpointId={} jobId={}", msg, endpointId, jobId, e);
            aiJobLogRepository.findById(jobId).ifPresent(job -> {
                job.setExecutionStatus(ExecutionStatus.FAILED);
                job.setCompletedAt(LocalDateTime.now());
                job.setErrorMessage(msg);
                aiJobLogRepository.save(job);
            });
            throw new AiPersistenceException(msg, e);
        }

        // 3. Build prompt with compact OpenAPI context
        String prompt;
        int selectedResponseStatus = 200;
        try {
            prompt = buildGenerateTestCasePrompt(compactContext, endpointId, jobId);
            selectedResponseStatus = extractSelectedResponseStatus(compactContext, 200);
            log.info("[GenerateTestCase] Prompt built endpointId={} jobId={} promptCharsBefore={} promptCharsAfter={} " +
                            "method={} path={} selectedStatus={} geminiTimeoutSeconds={}",
                    endpointId, jobId, compactContext.length(), prompt.length(),
                    httpMethod, endpointPath, selectedResponseStatus, geminiProperties.getTimeoutSeconds());
        } catch (Exception e) {
            log.error("[GenerateTestCase] Prompt build failed endpointId={} jobId={} cause={}",
                    endpointId, jobId, e.getMessage(), e);
            aiJobLogRepository.findById(jobId).ifPresent(job -> {
                job.setExecutionStatus(ExecutionStatus.FAILED);
                job.setCompletedAt(LocalDateTime.now());
                job.setErrorMessage("Prompt build failed: " + e.getMessage());
                aiJobLogRepository.save(job);
            });
            throw new AiPersistenceException("Prompt build failed: " + e.getMessage(), e);
        }

        String rawResult = null;
        boolean deterministicFallbackUsed = false;
        boolean safeReadEndpoint = isSafeReadMethod(endpoint.getHttpMethod());
        try {
            // 4. Route to AI provider — does NOT mark job SUCCESS yet.
            //    We only mark SUCCESS after parse + save both succeed.
            AiGeneratedTestCaseRequest testCaseRequest = null;
            if (safeReadEndpoint) {
                try {
                    rawResult = geminiApiClientService.generateText(prompt);
                } catch (Exception providerFailure) {
                    deterministicFallbackUsed = true;
                    testCaseRequest = buildDeterministicFallbackRequest(endpoint, selectedResponseStatus);
                    rawResult = buildDeterministicFallbackRawJson(testCaseRequest);
                    log.warn("[GenerateTestCase] Gemini failed for safe read endpoint; using deterministic fallback. " +
                                    "endpointId={} jobId={} method={} path={} selectedStatus={} fallbackUsed=true cause={}",
                            endpointId, jobId, httpMethod, endpointPath, selectedResponseStatus,
                            providerFailure.getMessage());
                }
            } else {
                rawResult = aiModelRouterService.routeAndExecuteForSkillRaw("GENERATE_TEST_CASE", prompt);
            }

            // 5. Parse AI output
            if (!deterministicFallbackUsed) {
                testCaseRequest = aiJsonParserService.parseTestCaseRequest(rawResult);
            }

            // 6. Persist test cases
            proxySelf.saveAiGeneratedTestCases(testCaseRequest, UUID.fromString(endpointId), jobId);

            // 7. Mark job SUCCESS only after both parse AND persist succeed
            int tokenInput = prompt.length() / 4;
            int tokenOutput = rawResult.length() / 4;
            String modelName = deterministicFallbackUsed
                    ? "deterministic-fallback"
                    : (safeReadEndpoint ? geminiProperties.getModel() : "router-selected");
            aiJobLogRepository.findById(jobId).ifPresent(job -> {
                job.setExecutionStatus(ExecutionStatus.SUCCESS);
                job.setCompletedAt(LocalDateTime.now());
                job.setTokenInput(tokenInput);
                job.setTokenOutput(tokenOutput);
                job.setModelName(modelName);
                aiJobLogRepository.save(job);
            });
            log.info("[GenerateTestCase] Job {} marked COMPLETED after successful parse+save. endpointId={} fallbackUsed={}",
                    jobId, endpointId, deterministicFallbackUsed);

        } catch (Exception e) {
            String aiFailMsg = "AI provider failed while generating test cases from OpenAPI document: "
                    + e.getMessage();
            log.error("[GenerateTestCase] {} endpointId={} jobId={}", aiFailMsg, endpointId, jobId);
            aiJobLogRepository.findById(jobId).ifPresent(job -> {
                job.setExecutionStatus(ExecutionStatus.FAILED);
                job.setCompletedAt(LocalDateTime.now());
                job.setErrorMessage(aiFailMsg);
                aiJobLogRepository.save(job);
            });
            if (!(e instanceof AiPersistenceException)) {
                throw new AiPersistenceException(aiFailMsg, e);
            }
            throw e;
        }

        return rawResult;
    }

    // =========================================================================
    // OpenAPI Context Helpers — STEP 4 (compact context for Agent 2 prompt)
    // =========================================================================

    ApiDocumentVersion resolveLatestOpenApiVersion(UUID projectId) {
        java.util.List<ApiDocumentVersion> versions =
                apiDocumentVersionRepository.findByApiDocumentSourceProjectIdOrderByVersionNoDesc(projectId);
        return versions.isEmpty() ? null : versions.get(0);
    }

    String buildCompactOpenApiContext(ApiEndpoint endpoint, ApiDocumentVersion version) {
        String methodKey = endpoint.getHttpMethod() != null
                ? endpoint.getHttpMethod().name().toLowerCase(java.util.Locale.ROOT)
                : "get";
        String pathKey = endpoint.getEndpointPath() != null ? endpoint.getEndpointPath() : "/";

        // Parse the full OpenAPI JSON
        com.fasterxml.jackson.databind.node.ObjectNode openApiRoot;
        try {
            openApiRoot = (com.fasterxml.jackson.databind.node.ObjectNode)
                    objectMapper.readTree(version.getContentJson());
        } catch (Exception e) {
            throw new BadRequestException(
                    "Cannot parse OpenAPI contentJson for version " + version.getId() + ": " + e.getMessage());
        }

        // Find operation node at paths[pathKey][methodKey]
        JsonNode operation = findOperationNode(openApiRoot, pathKey, methodKey);
        if (operation == null || operation.isMissingNode()) {
            throw new BadRequestException(
                    "OpenAPI operation not found for " + methodKey.toUpperCase(java.util.Locale.ROOT)
                            + " " + pathKey + " in latest API document.");
        }

        JsonNode responses = operation.path("responses");
        java.util.List<String> responseStatusCodes = new ArrayList<>();
        responses.fieldNames().forEachRemaining(responseStatusCodes::add);
        int selectedStatus = first2xxStatus(responses);
        JsonNode selectedResponse = responses.path(String.valueOf(selectedStatus));
        boolean hasExplicitExample = hasExplicitResponseExample(selectedResponse);
        com.fasterxml.jackson.databind.node.ObjectNode responseSchemaSummary =
                summarizeResponseSchema(openApiRoot, selectedResponse);
        boolean emptySchema = responseSchemaSummary.path("emptyProperties").asBoolean(false);

        log.info("[GenerateTestCase][OpenApi] Matched operation path={} method={} statusCodes={} " +
                        "selectedStatus={} hasExplicitExample={} emptySchema={}",
                pathKey, methodKey, responseStatusCodes, selectedStatus, hasExplicitExample, emptySchema);

        // Assemble compact context object
        com.fasterxml.jackson.databind.node.ObjectNode context =
                objectMapper.createObjectNode();
        context.put("source", "AGENT_1_OPENAPI_DOCUMENT");
        context.put("path", pathKey);
        context.put("method", methodKey);
        if (operation.hasNonNull("operationId")) {
            context.set("operationId", operation.get("operationId"));
        }
        com.fasterxml.jackson.databind.node.ArrayNode statusesNode = objectMapper.createArrayNode();
        responseStatusCodes.forEach(statusesNode::add);
        context.set("responseStatusCodes", statusesNode);
        context.put("selectedResponseStatus", selectedStatus);
        context.set("requestParams", compactRequestParams(operation));
        context.set("responseSchemaSummary", responseSchemaSummary);
        context.put("hasExplicitResponseExample", hasExplicitExample);
        if (!hasExplicitExample && emptySchema) {
            context.put("instruction",
                    "No stable response example is available. Generate status-code-only smoke test.");
        }

        // Minify to compact JSON to keep token count low
        try {
            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            throw new BadRequestException("Failed to serialize compact OpenAPI context: " + e.getMessage());
        }
    }

    /** Finds paths[normalizedPath][method] in an OpenAPI root node. */
    JsonNode findOperationNode(JsonNode openApiRoot, String path, String method) {
        JsonNode paths = openApiRoot.get("paths");
        if (paths == null || paths.isMissingNode()) return null;

        // Try exact path first, then normalized (trim trailing slash)
        String normalizedPath = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1) : path;

        JsonNode pathItem = paths.get(path);
        if (pathItem == null || pathItem.isMissingNode()) {
            pathItem = paths.get(normalizedPath);
        }
        if (pathItem == null || pathItem.isMissingNode()) return null;

        return pathItem.get(method);
    }

    /**
     * Recursively collects all "$ref": "#/components/schemas/Foo" schema names
     * referenced in the given JsonNode.
     */
    java.util.Set<String> collectSchemaRefs(JsonNode node) {
        java.util.Set<String> refs = new java.util.LinkedHashSet<>();
        collectSchemaRefsRecursive(node, refs, new java.util.HashSet<>(), 0);
        return refs;
    }

    private void collectSchemaRefsRecursive(JsonNode node, java.util.Set<String> refs,
                                            java.util.Set<String> visited, int depth) {
        if (node == null || depth > 20) return;
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual()) {
                String refValue = ref.asText();
                if (refValue.startsWith("#/components/schemas/")) {
                    String schemaName = refValue.substring("#/components/schemas/".length());
                    refs.add(schemaName);
                }
            }
            node.fields().forEachRemaining(entry ->
                    collectSchemaRefsRecursive(entry.getValue(), refs, visited, depth + 1));
        } else if (node.isArray()) {
            node.forEach(child ->
                    collectSchemaRefsRecursive(child, refs, visited, depth + 1));
        }
    }

    /**
     * Resolves schema definitions for the given ref names from components/schemas,
     * expanding nested $refs recursively (with a visited guard to prevent cycles).
     */
    com.fasterxml.jackson.databind.node.ObjectNode resolveRelatedSchemas(
            JsonNode openApiRoot, java.util.Set<String> refs, java.util.Set<String> visited) {
        com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
        JsonNode components = openApiRoot.get("components");
        if (components == null || components.isMissingNode()) return result;
        JsonNode schemas = components.get("schemas");
        if (schemas == null || schemas.isMissingNode()) return result;

        for (String schemaName : refs) {
            if (visited.contains(schemaName)) continue;
            visited.add(schemaName);
            JsonNode schemaDef = schemas.get(schemaName);
            if (schemaDef == null || schemaDef.isMissingNode()) continue;
            result.set(schemaName, schemaDef);
            // Resolve nested refs within this schema
            java.util.Set<String> nestedRefs = collectSchemaRefs(schemaDef);
            if (!nestedRefs.isEmpty()) {
                com.fasterxml.jackson.databind.node.ObjectNode nestedResolved =
                        resolveRelatedSchemas(openApiRoot, nestedRefs, visited);
                nestedResolved.fields().forEachRemaining(e -> result.set(e.getKey(), e.getValue()));
            }
        }
        return result;
    }

    private int first2xxStatus(JsonNode responses) {
        if (responses != null && responses.isObject()) {
            java.util.Iterator<String> fields = responses.fieldNames();
            while (fields.hasNext()) {
                String status = fields.next();
                try {
                    int code = Integer.parseInt(status);
                    if (code >= 200 && code < 300) {
                        return code;
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore default/non-numeric OpenAPI response keys.
                }
            }
        }
        return 200;
    }

    private com.fasterxml.jackson.databind.node.ArrayNode compactRequestParams(JsonNode operation) {
        com.fasterxml.jackson.databind.node.ArrayNode params = objectMapper.createArrayNode();
        JsonNode parameters = operation.path("parameters");
        if (!parameters.isArray()) {
            return params;
        }
        for (JsonNode parameter : parameters) {
            com.fasterxml.jackson.databind.node.ObjectNode compact = objectMapper.createObjectNode();
            copyTextField(parameter, compact, "name");
            copyTextField(parameter, compact, "in");
            if (parameter.has("required")) {
                compact.put("required", parameter.path("required").asBoolean(false));
            }
            JsonNode schema = parameter.path("schema");
            if (schema.hasNonNull("type")) {
                compact.set("type", schema.get("type"));
            }
            params.add(compact);
        }
        return params;
    }

    private void copyTextField(JsonNode source, com.fasterxml.jackson.databind.node.ObjectNode target, String fieldName) {
        if (source.hasNonNull(fieldName)) {
            target.set(fieldName, source.get(fieldName));
        }
    }

    private boolean hasExplicitResponseExample(JsonNode response) {
        if (response == null || response.isMissingNode()) {
            return false;
        }
        if (response.has("example") || response.has("examples")) {
            return true;
        }
        JsonNode content = response.path("content");
        if (content.isObject()) {
            java.util.Iterator<JsonNode> mediaTypes = content.elements();
            while (mediaTypes.hasNext()) {
                JsonNode media = mediaTypes.next();
                if (media.has("example") || media.has("examples") || media.path("schema").has("example")) {
                    return true;
                }
            }
        }
        return false;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode summarizeResponseSchema(JsonNode openApiRoot, JsonNode response) {
        com.fasterxml.jackson.databind.node.ObjectNode summary = objectMapper.createObjectNode();
        JsonNode schema = findResponseSchema(response);
        String schemaName = schemaNameFromRef(schema);
        JsonNode resolved = resolveSchema(openApiRoot, schema);

        if (schemaName != null) {
            summary.put("schemaName", schemaName);
        }
        if (resolved.hasNonNull("type")) {
            summary.set("type", resolved.get("type"));
        } else if (schema.hasNonNull("type")) {
            summary.set("type", schema.get("type"));
        }

        JsonNode properties = resolved.path("properties");
        com.fasterxml.jackson.databind.node.ArrayNode props = objectMapper.createArrayNode();
        if (properties.isObject()) {
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = properties.fields();
            int count = 0;
            while (fields.hasNext() && count < 8) {
                java.util.Map.Entry<String, JsonNode> field = fields.next();
                com.fasterxml.jackson.databind.node.ObjectNode prop = objectMapper.createObjectNode();
                prop.put("name", field.getKey());
                if (field.getValue().hasNonNull("type")) {
                    prop.set("type", field.getValue().get("type"));
                }
                props.add(prop);
                count++;
            }
        }
        summary.set("properties", props);
        summary.put("emptyProperties", props.isEmpty());
        return summary;
    }

    private JsonNode findResponseSchema(JsonNode response) {
        if (response == null || response.isMissingNode()) {
            return objectMapper.createObjectNode();
        }
        JsonNode content = response.path("content");
        JsonNode jsonMedia = content.path("application/json");
        if (!jsonMedia.isMissingNode()) {
            return jsonMedia.path("schema");
        }
        if (content.isObject()) {
            java.util.Iterator<JsonNode> mediaTypes = content.elements();
            if (mediaTypes.hasNext()) {
                return mediaTypes.next().path("schema");
            }
        }
        return objectMapper.createObjectNode();
    }

    private JsonNode resolveSchema(JsonNode openApiRoot, JsonNode schema) {
        String schemaName = schemaNameFromRef(schema);
        if (schemaName == null) {
            return schema == null || schema.isMissingNode() ? objectMapper.createObjectNode() : schema;
        }
        return openApiRoot.path("components").path("schemas").path(schemaName);
    }

    private String schemaNameFromRef(JsonNode schema) {
        if (schema == null || !schema.hasNonNull("$ref")) {
            return null;
        }
        String ref = schema.path("$ref").asText();
        String prefix = "#/components/schemas/";
        return ref.startsWith(prefix) ? ref.substring(prefix.length()) : null;
    }

    /** Builds the final Agent 2 prompt by injecting the compact OpenAPI context. */
    String buildGenerateTestCasePrompt(
            String compactOpenApiContext,
            String endpointId,
            UUID jobId
    ) {
        String prompt = AGENT_2_COMPACT_PROMPT_TEMPLATE
                .replace("{{OPENAPI_OPERATION_CONTEXT}}", nullToEmpty(compactOpenApiContext));

        if (prompt.contains("{{OPENAPI_OPERATION_CONTEXT}}")) {
            throw new IllegalStateException(
                    "Prompt template has unresolved placeholder OPENAPI_OPERATION_CONTEXT, endpointId="
                    + endpointId + ", jobId=" + jobId);
        }
        return prompt;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private int extractSelectedResponseStatus(String compactContext, int defaultStatus) {
        try {
            JsonNode root = objectMapper.readTree(compactContext);
            return root.path("selectedResponseStatus").asInt(defaultStatus);
        } catch (Exception ignored) {
            return defaultStatus;
        }
    }

    private boolean isSafeReadMethod(HttpMethod method) {
        return method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS;
    }

    private AiGeneratedTestCaseRequest buildDeterministicFallbackRequest(ApiEndpoint endpoint, int statusCode) {
        AiTestCaseAssertionDto assertion = AiTestCaseAssertionDto.builder()
                .assertionType("STATUS_CODE")
                .jsonPath("")
                .comparisonOperator("EQUALS")
                .expectedValue(String.valueOf(statusCode))
                .build();

        AiTestCaseItemDto item = AiTestCaseItemDto.builder()
                .testName("Smoke: " + endpoint.getHttpMethod() + " " + endpoint.getEndpointPath() + " returns " + statusCode)
                .caseType("SUCCESS")
                .priority("MEDIUM")
                .httpMethod(endpoint.getHttpMethod() != null ? endpoint.getHttpMethod() : HttpMethod.GET)
                .url(endpoint.getEndpointPath() != null ? endpoint.getEndpointPath() : "/")
                .expectedStatusCode(statusCode)
                .inputs(new ArrayList<>())
                .assertions(new ArrayList<>(List.of(assertion)))
                .build();

        AiGeneratedTestCaseRequest request = new AiGeneratedTestCaseRequest();
        request.setTestCases(new ArrayList<>(List.of(item)));
        return request;
    }

    private String buildDeterministicFallbackRawJson(AiGeneratedTestCaseRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            return "{\"test_cases\":[]}";
        }
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
            final String[] requestPathArray = new String[] {
                    apiEndpoint.getEndpointPath() != null ? apiEndpoint.getEndpointPath() : "/" };
            String queryParamsJson = null;
            String requestBodyJson = null;

            // 1. Thay thế Path Variables nếu có (ví dụ: {id} -> 123)
            if (dto.getPathParams() != null && dto.getPathParams().isObject()) {
                dto.getPathParams().fieldNames().forEachRemaining(fieldName -> {
                    String placeholder = "{" + fieldName + "}";
                    String value = dto.getPathParams().get(fieldName).asText();
                    requestPathArray[0] = requestPathArray[0].replace(placeholder, value);
                });
            }

            String requestPath = requestPathArray[0];

            if (apiEndpoint.getHttpMethod() == HttpMethod.GET && 
                ("VALIDATION_ERROR".equalsIgnoreCase(dto.getCaseType()) || "CLIENT_ERROR".equalsIgnoreCase(dto.getCaseType()))) {
                String pathPart = requestPath.contains("?") ? requestPath.substring(0, requestPath.indexOf("?")) : requestPath;
                boolean hasSuspicious = pathPart.contains("#") || pathPart.contains(" ") || pathPart.contains("&") ||
                                        pathPart.contains("=") || pathPart.contains("+") || pathPart.contains("%");
                if (hasSuspicious) {
                    boolean expects400 = false;
                    for (com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiAssertionDto aDto : dto.getAssertions()) {
                        if ("STATUS_CODE".equalsIgnoreCase(aDto.getAssertionType()) && "400".equals(aDto.getExpectedValue())) {
                            expects400 = true;
                            break;
                        }
                    }
                    if (expects400) {
                        int lastSlash = pathPart.lastIndexOf("/");
                        if (lastSlash != -1) {
                            String safeId = (pathPart.contains("customers") || pathPart.contains("customer")) ? "UNKNOWN_CUSTOMER_ID" : "UNKNOWN_ID";
                            String newPathPart = pathPart.substring(0, lastSlash + 1) + safeId;
                            requestPath = requestPath.contains("?") ? newPathPart + requestPath.substring(requestPath.indexOf("?")) : newPathPart;
                            
                            for (com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiAssertionDto aDto : dto.getAssertions()) {
                                if ("STATUS_CODE".equalsIgnoreCase(aDto.getAssertionType())) {
                                    aDto.setExpectedValue("404");
                                }
                                if (aDto.getTargetPath() != null && 
                                    (aDto.getTargetPath().contains("message") || aDto.getTargetPath().contains("error"))) {
                                    aDto.setTargetPath("$.error");
                                    aDto.setExpectedValue("Not Found");
                                    aDto.setOperator("CONTAINS");
                                }
                            }
                            if (caseName.contains("malformed")) {
                                caseName = caseName.replace("malformed", "unknown").replace("special characters", "unknown ID");
                            }
                        }
                    }
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
                            case "JSON_BODY", "BODY", "RESPONSE_BODY" -> AssertionType.JSON_PATH;
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

            // Xây dựng TestCase entity (Dùng constructor/setter thay vì builder để đảm bảo
            // Collections hoạt động chuẩn với JPA)
            assertions = aiTestCaseAssertionSanitizer.sanitize(
                    assertions,
                    apiEndpoint.getHttpMethod(),
                    caseType,
                    null,
                    false);

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
        // Map<String,Object> fields are always JSON objects by construction — no further check needed.
    }

    private void validateUpdateInput(UpdateTestCaseInputRequest input) {
        if (input == null) {
            throw new BadRequestException("input is required");
        }

        validateRequestPath(input.getRequestPath());
        // Map<String,Object> fields are always JSON objects by construction — no further check needed.
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

    // validateJsonObject for Map is not needed — Map<String,Object> is always a JSON object.
    // Kept for backward compatibility if called from other paths with JsonNode.
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
                .apiEndpoint(toApiEndpointResponse(testCase.getApiEndpoint()))
                .createdAt(testCase.getCreatedAt())
                .updatedAt(testCase.getUpdatedAt())
                .deletedAt(testCase.getDeletedAt())
                .build();
    }

    private ApiEndpointResponse toApiEndpointResponse(ApiEndpoint apiEndpoint) {
        if (apiEndpoint == null) {
            return null;
        }
        return ApiEndpointResponse.builder()
                .id(apiEndpoint.getId())
                .projectId(apiEndpoint.getSourceProject().getId())
                .sourceFileId(apiEndpoint.getSourceFile() == null ? null : apiEndpoint.getSourceFile().getId())
                .sourceUploadVersionId(apiEndpoint.getSourceUploadVersion() == null ? null : apiEndpoint.getSourceUploadVersion().getId())
                .controllerName(apiEndpoint.getControllerName())
                .methodName(apiEndpoint.getMethodName())
                .httpMethod(apiEndpoint.getHttpMethod())
                .endpointPath(apiEndpoint.getEndpointPath())
                .stableKey(apiEndpoint.getStableKey())
                .description(apiEndpoint.getDescription())
                .operationId(apiEndpoint.getOperationId())
                .tagName(apiEndpoint.getTagName())
                .authRequired(apiEndpoint.getAuthRequired())
                .deprecatedFlag(apiEndpoint.getDeprecatedFlag())
                .activeFlag(apiEndpoint.getActiveFlag())
                .staleFlag(apiEndpoint.getStaleFlag())
                .aiEnrichedFlag(apiEndpoint.getAiEnrichedFlag())
                .aiSummary(apiEndpoint.getAiSummary())
                .aiDescription(apiEndpoint.getAiDescription())
                .aiEnrichedAt(apiEndpoint.getAiEnrichedAt())
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
                .queryParamsJson(toJsonMap(input.getQueryParamsJson()))
                .headersJson(toJsonMap(input.getHeadersJson()))
                .requestBodyJson(toJsonMap(input.getRequestBodyJson()))
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

    /**
     * Serializes a {@code Map<String, Object>} to a JSON string for DB persistence.
     * Returns {@code null} when the map is null or empty.
     */
    private String toJsonString(java.util.Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ex) {
            throw new BadRequestException("Invalid JSON value.");
        }
    }

    /**
     * Serializes a {@link JsonNode} to a JSON string for DB persistence (used by AI flow).
     * Returns {@code null} when the node is null or a JSON null.
     */
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

    /**
     * Deserializes a stored JSON string back to a {@code Map<String, Object>} for API responses.
     * Returns {@code null} when the string is blank.
     */
    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> toJsonMap(String json) {
        if (!hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, java.util.Map.class);
        } catch (Exception ex) {
            // Stored value might not be a JSON object (e.g. array, primitive) — return as-is wrapped
            log.warn("[TestCaseService] Could not deserialize stored JSON to Map — field will be null. Value: {}", json);
            return null;
        }
    }

    /**
     * Deserializes a stored JSON string to a {@link JsonNode} — used only internally for AI flow.
     */
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

    @Override
    @Transactional(readOnly = true)
    public ApiEndpoint getEndpointWithDetails(UUID endpointId) {
        ApiEndpoint endpoint = apiEndpointRepository.findById(endpointId)
                .orElseThrow(() -> new ResourceNotFoundException("ApiEndpoint not found: " + endpointId));

        // Kích hoạt Lazy Loading một cách cưỡng bức (force fetch) trong Transaction
        if (endpoint.getApiParameters() != null) {
            Hibernate.initialize(endpoint.getApiParameters());
        }
        if (endpoint.getEndpointSchemaMaps() != null) {
            Hibernate.initialize(endpoint.getEndpointSchemaMaps());
            endpoint.getEndpointSchemaMaps().forEach(map -> {
                if (map.getApiSchema() != null) {
                    Hibernate.initialize(map.getApiSchema());
                }
            });
        }
        return endpoint;
    }

    private void sanitizeTestCaseDto(AiTestCaseItemDto itemDto, HttpMethod endpointMethod) {
        if (itemDto == null || endpointMethod != HttpMethod.GET || itemDto.getUrl() == null) {
            return;
        }

        String url = itemDto.getUrl();
        String pathPart = url.contains("?") ? url.substring(0, url.indexOf("?")) : url;

        // Check if path contains suspicious reserved characters
        boolean hasSuspicious = pathPart.contains("#") || pathPart.contains(" ") || pathPart.contains("&") ||
                                pathPart.contains("=") || pathPart.contains("+") || pathPart.contains("%");

        if (hasSuspicious) {
            boolean expects400 = false;
            if (itemDto.getAssertions() != null) {
                for (AiTestCaseAssertionDto assertionDto : itemDto.getAssertions()) {
                    if ("STATUS_CODE".equalsIgnoreCase(assertionDto.getAssertionType()) && "400".equals(assertionDto.getExpectedValue())) {
                        expects400 = true;
                        break;
                    }
                }
            }

            if (expects400) {
                int lastSlash = pathPart.lastIndexOf("/");
                if (lastSlash != -1) {
                    String safeId = (pathPart.contains("customers") || pathPart.contains("customer")) ? "UNKNOWN_CUSTOMER_ID" : "UNKNOWN_ID";
                    String newPathPart = pathPart.substring(0, lastSlash + 1) + safeId;
                    String newUrl = url.contains("?") ? newPathPart + url.substring(url.indexOf("?")) : newPathPart;
                    itemDto.setUrl(newUrl);

                    if (itemDto.getAssertions() != null) {
                        for (AiTestCaseAssertionDto assertionDto : itemDto.getAssertions()) {
                            if ("STATUS_CODE".equalsIgnoreCase(assertionDto.getAssertionType())) {
                                assertionDto.setExpectedValue("404");
                            }
                            if (assertionDto.getJsonPath() != null && 
                                (assertionDto.getJsonPath().contains("message") || assertionDto.getJsonPath().contains("error"))) {
                                assertionDto.setJsonPath("$.error");
                                assertionDto.setExpectedValue("Not Found");
                                assertionDto.setComparisonOperator("CONTAINS");
                            }
                        }
                    }

                    if (itemDto.getTestName() != null) {
                        itemDto.setTestName(itemDto.getTestName()
                            .replace("malformed", "unknown")
                            .replace("special characters", "unknown ID"));
                    }
                }
            }
        }
    }
}
