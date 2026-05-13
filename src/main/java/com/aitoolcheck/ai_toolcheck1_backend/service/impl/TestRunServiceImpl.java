package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.res.TestResultResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req.CreateTestRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req.ExecuteTestRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.HttpActualResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.TestRunDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.TestRunResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrunitem.res.TestRunItemResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RunStatus;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCase;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseAssertion;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestResult;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestRun;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestRunItem;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestCaseRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestRunItemRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestRunRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuleEngineService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestResultService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestRunService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.runner.ExecutedHttpResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.runner.TestHttpExecutor;
import com.aitoolcheck.ai_toolcheck1_backend.service.runner.TestRequestBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TestRunServiceImpl implements TestRunService {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final DateTimeFormatter RUN_CODE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final TestRunRepository testRunRepository;
    private final TestRunItemRepository testRunItemRepository;
    private final TestCaseRepository testCaseRepository;
    private final SourceProjectRepository sourceProjectRepository;
    private final TestRequestBuilder testRequestBuilder;
    private final TestHttpExecutor testHttpExecutor;
    private final ObjectMapper objectMapper;
    private final TestResultService testResultService;
    private final ProjectAccessService projectAccessService;
    private final RuleEngineService ruleEngineService;
    private TestRunService self;

    @org.springframework.beans.factory.annotation.Autowired
    @Lazy
    public void setSelf(TestRunService self) {
        this.self = self;
    }

    @Override
    @Transactional
    public TestRunDetailResponse create(CreateTestRunRequest request) {
        if (request == null) {
            throw new BadRequestException("CreateTestRunRequest is required");
        }

        if (request.getProjectId() == null) {
            throw new BadRequestException("projectId is required");
        }

        SourceProject sourceProject = projectAccessService.requireCanCreateTestRun(request.getProjectId());

        String runName = normalizeRequiredText(request.getRunName(), "runName");
        String description = normalizeOptionalText(request.getDescription());
        String baseUrl = normalizeBaseUrl(request.getBaseUrl());

        List<TestCase> resolvedCases = resolveTestCases(request, sourceProject.getId());

        List<TestRunItem> items = buildTestRunItems(resolvedCases);

        TestRun testRun = TestRun.builder()
                .sourceProject(sourceProject)
                .runCode(generateRunCode())
                .runName(runName)
                .description(description)
                .baseUrl(baseUrl)
                .environmentName(request.getEnvironmentName())
                .executionMode(request.getExecutionMode())
                .runStatus(RunStatus.PENDING)
                .testRunItems(items)
                .build();

        // Wire each item back to the run so cascade works
        items.forEach(item -> item.setTestRun(testRun));

        TestRun saved = testRunRepository.save(testRun);

        // Items are saved via CascadeType.ALL; load sorted for deterministic response
        List<TestRunItem> sortedItems = testRunItemRepository
                .findByTestRun_IdOrderBySortOrderAsc(saved.getId());

        return toDetailResponse(saved, sortedItems, Map.of());
    }

    @Override
    @Transactional
    public TestRunDetailResponse createTestRun(ExecuteTestRunRequest request) {
        log.info("Initializing test run with projectId={}, baseUrl={}",
                request.getProjectId(), request.getBaseUrl());

        if (request == null) {
            throw new BadRequestException("ExecuteTestRunRequest is required");
        }

        if (request.getProjectId() == null) {
            throw new BadRequestException("projectId is required");
        }

        // Verify SourceProject exists and check permissions
        SourceProject sourceProject = projectAccessService.requireCanCreateTestRun(request.getProjectId());

        log.debug("Found SourceProject: id={}, name={}", sourceProject.getId(), sourceProject.getProjectName());

        // Normalize and validate baseUrl
        String baseUrl = normalizeBaseUrl(request.getBaseUrl());

        // Resolve and validate test cases
        List<UUID> testCaseIds = request.getTestCaseIds();
        if (testCaseIds == null || testCaseIds.isEmpty()) {
            throw new BadRequestException("testCaseIds must not be empty");
        }

        // Reject null entries
        if (testCaseIds.contains(null)) {
            throw new BadRequestException("testCaseIds must not contain null values");
        }

        // Deduplicate while preserving request order
        List<UUID> deduplicatedIds = new ArrayList<>(new LinkedHashSet<>(testCaseIds));

        // Fetch test cases from database
        List<TestCase> testCases = testCaseRepository
                .findByIdInAndSourceProject_IdAndActiveFlagTrueAndDeletedFlagFalse(
                        deduplicatedIds, sourceProject.getId());

        if (testCases.isEmpty()) {
            throw new BadRequestException("No active test cases found for the specified IDs and project");
        }

        if (testCases.size() != deduplicatedIds.size()) {
            throw new BadRequestException(
                    "Some selected test cases are missing, inactive, deleted, or not in this project");
        }

        log.debug("Resolved {} test cases", testCases.size());

        // Reorder test cases to match request order
        Map<UUID, TestCase> testCaseById = new HashMap<>();
        testCases.forEach(tc -> testCaseById.put(tc.getId(), tc));

        List<TestCase> orderedTestCases = new ArrayList<>(deduplicatedIds.size());
        for (UUID caseId : deduplicatedIds) {
            orderedTestCases.add(testCaseById.get(caseId));
        }

        // Build TestRunItems from test cases
        List<TestRunItem> testRunItems = buildTestRunItems(orderedTestCases);

        // Determine runCode: use provided value or generate
        String runCode = request.getRunCode();
        if (!hasText(runCode)) {
            runCode = generateRunCode();
        }

        log.debug("Generated/assigned runCode={}", runCode);

        // Create TestRun entity
        TestRun testRun = TestRun.builder()
                .sourceProject(sourceProject)
                .runCode(runCode)
                .runName(runCode) // Use runCode as runName since ExecuteTestRunRequest doesn't have runName
                .baseUrl(baseUrl)
                .environmentName(request.getEnvironmentName())
                .runStatus(RunStatus.RUNNING) // Set to RUNNING as per requirement
                .testRunItems(testRunItems)
                .build();

        // Wire each item back to the run for cascade persistence
        testRunItems.forEach(item -> item.setTestRun(testRun));

        // Save TestRun (items saved via CascadeType.ALL)
        TestRun savedTestRun = testRunRepository.save(testRun);
        log.info("Test run created successfully: id={}, runCode={}, totalItems={}",
                savedTestRun.getId(), savedTestRun.getRunCode(), testRunItems.size());

        // Trigger Async Execution only after transaction commit to avoid race condition
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        self.executeTestRunAsync(savedTestRun.getId());
                    }
                });

        // Load sorted test run items for deterministic response
        List<TestRunItem> sortedItems = testRunItemRepository
                .findByTestRun_IdOrderBySortOrderAsc(savedTestRun.getId());

        return toDetailResponse(savedTestRun, sortedItems, Map.of());
    }

    @Override
    @Async
    @Transactional
    public void executeTestRunAsync(UUID id) {
        log.info("Starting async execution for TestRun id: {}", id);

        TestRun testRun = testRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TestRun not found with id: " + id));

        testRun.setRunStatus(RunStatus.RUNNING);
        testRunRepository.save(testRun);

        List<TestRunItem> items = testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(id);

        boolean anyFailed = false;

        for (TestRunItem item : items) {
            try {
                item.setItemStatus(ExecutionStatus.RUNNING);
                testRunItemRepository.save(item);

                TestCase testCase = item.getTestCase();
                TestCaseInput input = testCase.getTestCaseInput();

                if (input == null) {
                    log.warn("TestCaseInput missing for item id: {}", item.getId());
                    item.setItemStatus(ExecutionStatus.FAILED);
                    testRunItemRepository.save(item);
                    anyFailed = true;
                    continue;
                }

                // 1. Build prepared request (pure frame — no HTTP execution)
                PreparedHttpRequestResponse prepared = testRequestBuilder.build(
                        testRun.getBaseUrl(), input);

                // 2. Execute real HTTP via dedicated executor
                ExecutedHttpResponse executed = testHttpExecutor.execute(prepared);
                HttpActualResponseDto actualResponse = toHttpActualResponse(executed);

                // 3. Persist raw result (No legacy evaluation)
                TestResult rawTestResult = testResultService.saveRawTestResult(item, actualResponse);

                // 4. Evaluate using RuleEngineService (Single Source of Truth)
                RuleEngineResultDto ruleResult = ruleEngineService.evaluate(rawTestResult.getId());

                ExecutionStatus itemStatus = resolveItemStatus(ruleResult.getFinalStatus());
                item.setItemStatus(itemStatus);
                testRunItemRepository.save(item);

                if (itemStatus == ExecutionStatus.FAILED) {
                    anyFailed = true;
                }

            } catch (Exception e) {
                log.error("Error executing TestRunItem id: {}", item.getId(), e);
                item.setItemStatus(ExecutionStatus.FAILED);
                testRunItemRepository.save(item);
                anyFailed = true;
            }
        }

        testRun.setRunStatus(anyFailed ? RunStatus.FAILED : RunStatus.COMPLETED);
        testRunRepository.save(testRun);
        log.info("Finished async execution for TestRun id: {}", id);
    }

    @Override
    @Transactional
    public TestRunDetailResponse execute(UUID id) {
        if (id == null) {
            throw new BadRequestException("id is required");
        }

        // Load and validate TestRun
        TestRun testRun = testRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TestRun not found with id: " + id));

        // Guard: reject if already in progress (prevents concurrent double-execution)
        if (testRun.getRunStatus() == RunStatus.RUNNING) {
            throw new BadRequestException(
                    "TestRun is already in RUNNING state. Wait for it to complete before re-executing.");
        }

        // Permission: mode-aware — SAFE_WRITE/FULL_WRITE require MAINTAINER
        projectAccessService.requireCanExecuteTestRun(
                testRun.getSourceProject().getId(),
                testRun.getExecutionMode());

        // Load items in deterministic order
        List<TestRunItem> items = testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(id);
        if (items.isEmpty()) {
            throw new BadRequestException("TestRun has no items to execute");
        }

        // Mark run as RUNNING
        testRun.setRunStatus(RunStatus.RUNNING);
        testRunRepository.save(testRun);

        boolean anyFailed = false;

        for (TestRunItem item : items) {
            try {
                item.setItemStatus(ExecutionStatus.RUNNING);
                testRunItemRepository.save(item);

                TestCase testCase = item.getTestCase();
                TestCaseInput input = testCase.getTestCaseInput();

                if (input == null) {
                    log.warn("[execute] No TestCaseInput for item id={}, testCase id={}", item.getId(), testCase.getId());
                    item.setItemStatus(ExecutionStatus.FAILED);
                    testRunItemRepository.save(item);
                    saveErrorResult(item, null, "TestCaseInput is missing for this test case");
                    anyFailed = true;
                    continue;
                }

                // Build prepared request (pure frame — no HTTP)
                PreparedHttpRequestResponse prepared = testRequestBuilder.build(
                        testRun.getBaseUrl(), input);

                // Execute real HTTP via dedicated executor
                ExecutedHttpResponse executed = testHttpExecutor.execute(prepared);
                HttpActualResponseDto actualResponse = toHttpActualResponse(executed);

                // Persist raw result (No legacy evaluation)
                TestResult rawTestResult = testResultService.saveRawTestResult(item, actualResponse);

                // Evaluate using RuleEngineService (Single Source of Truth)
                RuleEngineResultDto ruleResult = ruleEngineService.evaluate(rawTestResult.getId());

                // Map ResultStatus -> ExecutionStatus for item
                ExecutionStatus itemStatus = resolveItemStatus(ruleResult.getFinalStatus());
                item.setItemStatus(itemStatus);
                testRunItemRepository.save(item);

                if (itemStatus == ExecutionStatus.FAILED) {
                    anyFailed = true;
                }

            } catch (Exception e) {
                log.error("[execute] Unexpected error for TestRunItem id={}: {}", item.getId(), e.getMessage());
                item.setItemStatus(ExecutionStatus.FAILED);
                testRunItemRepository.save(item);
                saveErrorResult(item, null, "Unexpected execution error: " + truncateSafe(e.getMessage(), 500));
                anyFailed = true;
            }
        }

        // Aggregate run status
        testRun.setRunStatus(anyFailed ? RunStatus.FAILED : RunStatus.COMPLETED);
        TestRun savedRun = testRunRepository.save(testRun);

        // Reload items with results for response
        List<TestRunItem> finalItems = testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(savedRun.getId());
        return toDetailResponse(savedRun, finalItems, Map.of());
    }

    /**
     * Map ResultStatus to ExecutionStatus for TestRunItem.
     * PASS -> SUCCESS, FAIL -> FAILED, ERROR -> FAILED, SKIPPED -> FAILED (safe fallback).
     */
    private ExecutionStatus resolveItemStatus(ResultStatus resultStatus) {
        if (resultStatus == null) {
            return ExecutionStatus.FAILED;
        }
        return switch (resultStatus) {
            case PASS -> ExecutionStatus.SUCCESS;
            case FAIL, ERROR, SKIPPED -> ExecutionStatus.FAILED;
        };
    }

    /**
     * Save an ERROR result for a TestRunItem when execution itself cannot proceed
     * (e.g. missing input, unexpected exception before HTTP call).
     */
    private void saveErrorResult(TestRunItem item, Integer statusCode, String errorMessage) {
        HttpActualResponseDto errResponse = HttpActualResponseDto.builder()
                .statusCode(statusCode != null ? statusCode : 0)
                .responseBody(null)
                .responseTimeMs(0L)
                .errorMessage(errorMessage)
                .build();

        // 1. Save raw result with error message
        TestResult rawTestResult = testResultService.saveRawTestResult(item, errResponse);
        
        // 2. We can directly set ERROR to TestResult and item status since it's a pre-HTTP execution error
        rawTestResult.setResultStatus(ResultStatus.ERROR);
        // Note: RuleEngineService won't be called here as we don't have enough data
    }

    /**
     * Bridge: converts {@link ExecutedHttpResponse} from {@link TestHttpExecutor}
     * into the {@link HttpActualResponseDto} expected by {@link com.aitoolcheck.ai_toolcheck1_backend.service.TestResultService}.
     */
    private HttpActualResponseDto toHttpActualResponse(ExecutedHttpResponse executed) {
        return HttpActualResponseDto.builder()
                .statusCode(executed.statusCode())
                .responseBody(executed.responseBody())
                .responseTimeMs(executed.responseTimeMs())
                .errorMessage(executed.errorMessage())
                .build();
    }

    private String truncateSafe(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    @Override
    @Transactional(readOnly = true)
    public TestRunDetailResponse getById(UUID id) {
        if (id == null) {
            throw new BadRequestException("id is required");
        }

        TestRun testRun = testRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "TestRun not found with id: " + id));
        projectAccessService.requireCanViewProject(testRun.getSourceProject().getId());

        List<TestRunItem> items = testRunItemRepository
                .findByTestRun_IdOrderBySortOrderAsc(id);

        return toDetailResponse(testRun, items, Map.of());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TestRunResponse> getByProjectId(UUID projectId) {
        if (projectId == null) {
            throw new BadRequestException("projectId is required");
        }

        projectAccessService.requireCanViewProject(projectId);

        List<TestRun> runs = testRunRepository
                .findBySourceProject_IdOrderByCreatedAtDesc(projectId);

        return runs.stream()
                .map(run -> {
                    int totalItems = (int) testRunItemRepository.countByTestRun_Id(run.getId());
                    return toResponse(run, totalItems);
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TestRunDetailResponse prepare(UUID id) {
        if (id == null) {
            throw new BadRequestException("id is required");
        }

        TestRun testRun = testRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "TestRun not found with id: " + id));
        projectAccessService.requireCanPrepareTestRun(testRun.getSourceProject().getId());

        List<TestRunItem> items = testRunItemRepository
                .findByTestRun_IdOrderBySortOrderAsc(id);

        Map<UUID, PreparedHttpRequestResponse> preparedByItemId = new HashMap<>();

        for (TestRunItem item : items) {
            TestCase testCase = item.getTestCase();
            TestCaseInput input = testCase.getTestCaseInput();

            if (input == null) {
                throw new BadRequestException(
                        "TestCaseInput is missing for testCase id: " + testCase.getId());
            }

            PreparedHttpRequestResponse prepared = testRequestBuilder.build(testRun.getBaseUrl(), input);

            preparedByItemId.put(item.getId(), prepared);
        }

        return toDetailResponse(testRun, items, preparedByItemId);
    }

    private List<TestCase> resolveTestCases(CreateTestRunRequest request, UUID projectId) {
        List<UUID> rawIds = request.getTestCaseIds();
        boolean hasExplicitIds = rawIds != null && !rawIds.isEmpty();
        boolean includeAll = Boolean.TRUE.equals(request.getIncludeAllActive());

        if (hasExplicitIds) {
            // Reject null entries before deduplication
            if (rawIds.contains(null)) {
                throw new BadRequestException("testCaseIds must not contain null values");
            }

            // Deduplicate while preserving request order
            List<UUID> requestedIds = new ArrayList<>(new LinkedHashSet<>(rawIds));

            List<TestCase> resolved = testCaseRepository
                    .findByIdInAndSourceProject_IdAndActiveFlagTrueAndDeletedFlagFalse(
                            requestedIds, projectId);

            if (resolved.size() != requestedIds.size()) {
                throw new BadRequestException(
                        "Some selected test cases are missing, inactive, deleted, or not in project");
            }

            // Reorder to match explicit request order — repository does not guarantee order
            Map<UUID, TestCase> byId = new HashMap<>();
            resolved.forEach(tc -> byId.put(tc.getId(), tc));

            List<TestCase> ordered = new ArrayList<>(requestedIds.size());
            for (UUID uid : requestedIds) {
                ordered.add(byId.get(uid));
            }
            return ordered;

        } else if (includeAll) {
            List<TestCase> all = testCaseRepository
                    .findBySourceProject_IdAndActiveFlagTrueAndDeletedFlagFalseOrderByUpdatedAtDesc(
                            projectId);

            if (all.isEmpty()) {
                throw new BadRequestException(
                        "No active test cases found for test run");
            }
            return all;

        } else {
            throw new BadRequestException(
                    "At least one testCaseId is required unless includeAllActive is true");
        }
    }

    private List<TestRunItem> buildTestRunItems(List<TestCase> testCases) {
        List<TestRunItem> items = new ArrayList<>(testCases.size());
        int sortOrder = 1;
        for (TestCase testCase : testCases) {
            items.add(TestRunItem.builder()
                    .testCase(testCase)
                    .sortOrder(sortOrder++)
                    .itemStatus(ExecutionStatus.PENDING)
                    .build());
        }
        return items;
    }

    private TestRunResponse toResponse(TestRun run, int totalItems) {
        return TestRunResponse.builder()
                .id(run.getId())
                .projectId(run.getSourceProject().getId())
                .runCode(run.getRunCode())
                .runName(run.getRunName())
                .description(run.getDescription())
                .baseUrl(run.getBaseUrl())
                .environmentName(run.getEnvironmentName())
                .executionMode(run.getExecutionMode())
                .runStatus(run.getRunStatus())
                .totalItems(totalItems)
                .createdAt(run.getCreatedAt())
                .updatedAt(run.getUpdatedAt())
                .build();
    }

    private TestRunDetailResponse toDetailResponse(
            TestRun run,
            List<TestRunItem> items,
            Map<UUID, PreparedHttpRequestResponse> preparedByItemId) {

        List<TestRunItemResponse> itemResponses = items.stream()
                .map(item -> toItemResponse(item, preparedByItemId.get(item.getId())))
                .toList();

        return TestRunDetailResponse.builder()
                .id(run.getId())
                .projectId(run.getSourceProject().getId())
                .runCode(run.getRunCode())
                .runName(run.getRunName())
                .description(run.getDescription())
                .baseUrl(run.getBaseUrl())
                .environmentName(run.getEnvironmentName())
                .executionMode(run.getExecutionMode())
                .runStatus(run.getRunStatus())
                .totalItems(itemResponses.size())
                .items(itemResponses)
                .createdAt(run.getCreatedAt())
                .updatedAt(run.getUpdatedAt())
                .build();
    }

    private TestRunItemResponse toItemResponse(
            TestRunItem item,
            PreparedHttpRequestResponse preparedRequest) {

        TestCase testCase = item.getTestCase();
        TestResult testResult = item.getTestResult();

        return TestRunItemResponse.builder()
                .id(item.getId())
                .testRunId(item.getTestRun().getId())
                .testCaseId(testCase.getId())
                .caseCode(testCase.getCaseCode())
                .caseName(testCase.getCaseName())
                .sortOrder(item.getSortOrder())
                .itemStatus(item.getItemStatus())
                .preparedRequest(preparedRequest)
                .result(toResultResponse(testResult))
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .build();
    }

    private TestResultResponse toResultResponse(TestResult result) {
        if (result == null) {
            return null;
        }

        String actualResponseJson = result.getActualResponseJson();

        return TestResultResponse.builder()
                .id(result.getId())
                .testRunItemId(result.getTestRunItem().getId())
                .actualStatus(result.getActualStatus())
                .resultStatus(result.getResultStatus())
                .responseTimeMs(result.getResponseTimeMs())
                .actualResponseJson(actualResponseJson)
                .errorMessage(result.getErrorMessage())
                .blockedReason(result.getBlockedReason())
                .createdAt(result.getCreatedAt())
                .updatedAt(result.getUpdatedAt())
                .build();
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

    private String normalizeBaseUrl(String baseUrl) {
        if (!hasText(baseUrl)) {
            throw new BadRequestException("baseUrl is required");
        }

        String trimmed = baseUrl.trim();
        validateBaseUrl(trimmed);

        // Strip trailing slash — consistent with TestRequestBuilder
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private void validateBaseUrl(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException ex) {
            throw new BadRequestException("baseUrl is invalid");
        }

        String scheme = uri.getScheme();
        if (scheme == null || scheme.isBlank()) {
            throw new BadRequestException("baseUrl is invalid");
        }

        if (!ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new BadRequestException("baseUrl must use http or https");
        }

        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BadRequestException("baseUrl is invalid");
        }
    }

    private String generateRunCode() {
        String timestamp = LocalDateTime.now().format(RUN_CODE_FORMATTER);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "TR-" + timestamp + "-" + suffix;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}