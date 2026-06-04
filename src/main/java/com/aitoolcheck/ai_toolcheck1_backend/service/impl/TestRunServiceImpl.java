package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.TestRunStaleProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.RuleEngineResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testresult.res.TestResultResponse;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestFailureAnalysisRepository;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestFailureAnalysis;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testfailureanalysis.res.TestFailureAnalysisDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req.CreateTestRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req.ExecuteTestRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.HttpActualResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.TestRunDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.TestRunResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrunitem.res.TestRunItemResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationSeverity;
import com.aitoolcheck.ai_toolcheck1_backend.enums.NotificationType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
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
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestResultRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestRunItemRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestRunRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuleEngineService;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceRuntimeService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestResultService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestRunService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.notification.ProjectNotificationEventPublisher;
import com.aitoolcheck.ai_toolcheck1_backend.service.runner.ExecutedHttpResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.runner.TestHttpExecutor;
import com.aitoolcheck.ai_toolcheck1_backend.service.runner.TestRequestBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.realtime.TestRunRealtimeEvent;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestRunRealtimePublisher;

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
    private static final String STALE_PENDING_REASON =
            "Test run expired while pending. Please create or execute a new run.";
    private static final String STALE_RUNNING_REASON =
            "Test run exceeded maximum execution time and was marked failed.";

    private final TestRunRepository testRunRepository;
    private final TestRunItemRepository testRunItemRepository;
    private final TestCaseRepository testCaseRepository;
    private final SourceProjectRepository sourceProjectRepository;
    private final TestResultRepository testResultRepository;
    private final TestRequestBuilder testRequestBuilder;
    private final TestHttpExecutor testHttpExecutor;
    private final ObjectMapper objectMapper;
    private final TestResultService testResultService;
    private final ProjectAccessService projectAccessService;
    private final RuleEngineService ruleEngineService;
    private final TransactionTemplate transactionTemplate;
    private final TestRunRealtimePublisher testRunRealtimePublisher;
    private final ProjectNotificationEventPublisher notificationEventPublisher;
    private final TestFailureAnalysisRepository testFailureAnalysisRepository;
    private final TestRunStaleProperties testRunStaleProperties;
    private final SourceRuntimeService sourceRuntimeService;
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
        RuntimeMode runtimeMode = resolveRuntimeMode(request.getRuntimeMode());
        // Resolve effective baseUrl: request → UP SourceRuntime → project.defaultTargetBaseUrl → 400.
        // SourceProject.repositoryUrl (GitHub source URL) is NEVER used here.
        String baseUrl = sourceRuntimeService.resolveBaseUrlForTestRun(
                sourceProject.getId(), request.getBaseUrl(), sourceProject.getDefaultTargetBaseUrl());

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
                .runtimeMode(runtimeMode)
                .targetBaseUrlUsed(baseUrl)
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

        RuntimeMode runtimeMode = resolveRuntimeMode(request.getRuntimeMode());
        // Resolve effective baseUrl: request → UP SourceRuntime → project.defaultTargetBaseUrl → 400.
        // SourceProject.repositoryUrl (GitHub source URL) is NEVER used here.
        String baseUrl = sourceRuntimeService.resolveBaseUrlForTestRun(
                sourceProject.getId(), request.getBaseUrl(), sourceProject.getDefaultTargetBaseUrl());

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

        String runName = normalizeOptionalText(request.getRunName());
        if (!hasText(runName)) {
            runName = runCode;
        } else if (runName.length() > 150) {
            runName = runName.substring(0, 150);
        }

        String description = normalizeOptionalText(request.getDescription());
        if (description != null && description.length() > 2000) {
            description = description.substring(0, 2000);
        }

        ExecutionMode executionMode = request.getExecutionMode();
        if (executionMode == null) {
            executionMode = ExecutionMode.READ_ONLY;
        }

        log.debug("Generated/assigned runCode={}", runCode);

        // Create TestRun entity
        TestRun testRun = TestRun.builder()
                .sourceProject(sourceProject)
                .runCode(runCode)
                .runName(runName)
                .description(description)
                .baseUrl(baseUrl)
                .environmentName(request.getEnvironmentName())
                .executionMode(executionMode)
                .runtimeMode(runtimeMode)
                .targetBaseUrlUsed(baseUrl)
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
    public void executeTestRunAsync(UUID id) {
        log.info("Starting async execution for TestRun id: {}", id);

        TestRun testRun = testRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TestRun not found with id: " + id));

        List<TestRunItem> items = testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(id);

        if (!performPreflightCheck(testRun, items)) {
            log.warn("Preflight check failed for TestRun id: {}", id);
            return;
        }

        testRun.setRunStatus(RunStatus.RUNNING);
        testRunRepository.save(testRun);

        boolean anyFailed = false;

        for (TestRunItem item : items) {
            try {
                // Check skipped/blocked first
                String skipReason = getSkippedReason(item, testRun.getExecutionMode());
                if (skipReason != null) {
                    item.setItemStatus(ExecutionStatus.FAILED);
                    testRunItemRepository.save(item);
                    saveSkippedResult(item.getId(), skipReason);
                    continue;
                }

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
                // Guard: if requestPath still has unresolved {variable} placeholders, mark ERROR early.
                String requestPath = input.getRequestPath();
                if (requestPath != null && requestPath.matches(".*\\{[^}]+}.*")) {
                    log.warn("[executeTestRunAsync] Unresolved path variable in requestPath='{}' for item id={}",
                            requestPath, item.getId());
                    item.setItemStatus(ExecutionStatus.FAILED);
                    testRunItemRepository.save(item);
                    anyFailed = true;
                    continue;
                }
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
        publishTestRunNotification(testRun);
        log.info("Finished async execution for TestRun id: {}", id);
    }

    @Override
    public TestRunDetailResponse execute(UUID id) {
        if (id == null) {
            throw new BadRequestException("id is required");
        }

        TestRun preflightRun = testRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TestRun not found with id: " + id));

        if (isStale(preflightRun)) {
            String staleReason = resolveStaleReason(preflightRun);
            markRunFailed(preflightRun, staleReason);
            throw new BadRequestException(staleReason);
        }

        if (preflightRun.getRunStatus() == RunStatus.RUNNING) {
            throw new BadRequestException("TestRun is already in RUNNING state. Wait for it to complete before re-executing.");
        }

        projectAccessService.requireCanExecuteTestRun(
                preflightRun.getSourceProject().getId(),
                preflightRun.getExecutionMode());

        List<TestRunItem> preflightItems = testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(id);
        if (preflightItems.isEmpty()) {
            throw new BadRequestException("TestRun has no items to execute");
        }

        if (!performPreflightCheck(preflightRun, preflightItems)) {
            throw new BadRequestException("Base URL/runtime does not match uploaded source. All selected safe endpoints returned 404.");
        }

        ExecutionContext executionContext = transactionTemplate.execute(status -> {
            TestRun testRun = testRunRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("TestRun not found with id: " + id));

            if (testRun.getRunStatus() == RunStatus.RUNNING) {
                throw new BadRequestException(
                        "TestRun is already in RUNNING state. Wait for it to complete before re-executing.");
            }

            testRun.setRunStatus(RunStatus.RUNNING);
            TestRun savedRun = testRunRepository.save(testRun);

            List<UUID> itemIds = preflightItems.stream()
                    .map(TestRunItem::getId)
                    .toList();
            return new ExecutionContext(
                    savedRun.getId(),
                    savedRun.getSourceProject().getId(),
                    savedRun.getBaseUrl(),
                    itemIds,
                    itemIds.size()
            );
        });

        RealtimeExecutionCounters counters = new RealtimeExecutionCounters(executionContext.totalItems());
        testRunRealtimePublisher.publishRunStarted(
                buildRunRealtimeEvent(executionContext, RunStatus.RUNNING, counters)
        );

        boolean anyFailed = false;

        for (UUID itemId : executionContext.itemIds()) {
            try {
                ItemExecutionContext itemContext = prepareItemExecution(itemId, executionContext.baseUrl());
                testRunRealtimePublisher.publishItemStarted(
                        buildItemStartedRealtimeEvent(executionContext, itemContext, counters)
                );

                if (itemContext.missingInput()) {
                    log.warn("[execute] No TestCaseInput for item id={}, testCase id={}",
                            itemContext.itemId(), itemContext.testCaseId());
                    markItemStatus(itemContext.itemId(), ExecutionStatus.FAILED);
                    saveErrorResult(itemContext.itemId(), null, "TestCaseInput is missing for this test case");
                    RealtimeItemSnapshot completedSnapshot = loadRealtimeItemSnapshot(itemContext.itemId());
                    counters.markCompleted(completedSnapshot.resultStatus());
                    testRunRealtimePublisher.publishItemCompleted(
                            buildItemCompletedRealtimeEvent(executionContext, completedSnapshot, counters)
                    );
                    anyFailed = true;
                    continue;
                }

                // Check if skipped/blocked
                TestRunItem currentItem = transactionTemplate.execute(status -> 
                    testRunItemRepository.findById(itemId).orElse(null)
                );
                TestRun currentRun = transactionTemplate.execute(status -> 
                    testRunRepository.findById(executionContext.runId()).orElse(null)
                );
                String skipReason = getSkippedReason(currentItem, currentRun != null ? currentRun.getExecutionMode() : null);
                if (skipReason != null) {
                    markItemStatus(itemId, ExecutionStatus.FAILED);
                    saveSkippedResult(itemId, skipReason);
                    RealtimeItemSnapshot completedSnapshot = loadRealtimeItemSnapshot(itemId);
                    counters.markCompleted(completedSnapshot.resultStatus());
                    testRunRealtimePublisher.publishItemCompleted(
                            buildItemCompletedRealtimeEvent(executionContext, completedSnapshot, counters)
                    );
                    continue;
                }

                // Execute real HTTP via dedicated executor
                ExecutedHttpResponse executed = testHttpExecutor.execute(itemContext.preparedRequest());
                HttpActualResponseDto actualResponse = toHttpActualResponse(executed);

                // Persist raw result (No legacy evaluation)
                TestResult rawTestResult = transactionTemplate.execute(status -> {
                    TestRunItem item = testRunItemRepository.findById(itemContext.itemId())
                            .orElseThrow(() -> new ResourceNotFoundException(
                                    "TestRunItem not found with id: " + itemContext.itemId()));
                    return testResultService.saveRawTestResult(item, actualResponse);
                });

                // Evaluate using RuleEngineService (Single Source of Truth)
                RuleEngineResultDto ruleResult = ruleEngineService.evaluate(rawTestResult.getId());

                // Map ResultStatus -> ExecutionStatus for item
                ExecutionStatus itemStatus = resolveItemStatus(ruleResult.getFinalStatus());
                markItemStatus(itemContext.itemId(), itemStatus);

                RealtimeItemSnapshot completedSnapshot = loadRealtimeItemSnapshot(itemContext.itemId());
                counters.markCompleted(completedSnapshot.resultStatus());
                testRunRealtimePublisher.publishItemCompleted(
                        buildItemCompletedRealtimeEvent(executionContext, completedSnapshot, counters)
                );

                if (itemStatus == ExecutionStatus.FAILED) {
                    anyFailed = true;
                }

            } catch (Exception e) {
                log.error("[execute] Unexpected error for TestRunItem id={}: {}", itemId, e.getMessage());
                markItemStatus(itemId, ExecutionStatus.FAILED);
                saveErrorResult(itemId, null, "Unexpected execution error: " + truncateSafe(e.getMessage(), 500));
                RealtimeItemSnapshot completedSnapshot = loadRealtimeItemSnapshot(itemId);
                counters.markCompleted(completedSnapshot.resultStatus());
                testRunRealtimePublisher.publishItemCompleted(
                        buildItemCompletedRealtimeEvent(executionContext, completedSnapshot, counters)
                );
                anyFailed = true;
            }
        }

        // Aggregate run status
        boolean finalAnyFailed = anyFailed;
        TestRun savedRun = transactionTemplate.execute(status -> {
            TestRun testRun = testRunRepository.findById(executionContext.runId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "TestRun not found with id: " + executionContext.runId()));
            testRun.setRunStatus(finalAnyFailed ? RunStatus.FAILED : RunStatus.COMPLETED);
            return testRunRepository.save(testRun);
        });

        TestRunRealtimeEvent finalRunEvent = buildRunRealtimeEvent(
                executionContext,
                savedRun.getRunStatus(),
                counters
        );
        if (savedRun.getRunStatus() == RunStatus.COMPLETED) {
            testRunRealtimePublisher.publishRunCompleted(finalRunEvent);
        } else {
            testRunRealtimePublisher.publishRunFailed(finalRunEvent);
        }
        publishTestRunNotification(savedRun);

        return transactionTemplate.execute(status -> {
            TestRun finalRun = testRunRepository.findById(savedRun.getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "TestRun not found with id: " + savedRun.getId()));
            List<TestRunItem> finalItems = testRunItemRepository.findByTestRun_IdOrderBySortOrderAsc(finalRun.getId());
            return toDetailResponse(finalRun, finalItems, Map.of());
        });
    }

    private ItemExecutionContext prepareItemExecution(UUID itemId, String baseUrl) {
        return transactionTemplate.execute(status -> {
            TestRunItem item = testRunItemRepository.findById(itemId)
                    .orElseThrow(() -> new ResourceNotFoundException("TestRunItem not found with id: " + itemId));

            item.setItemStatus(ExecutionStatus.RUNNING);
            testRunItemRepository.save(item);

            TestCase testCase = item.getTestCase();
            TestCaseInput input = testCase.getTestCaseInput();
            if (input == null) {
                return new ItemExecutionContext(
                        item.getId(),
                        testCase.getId(),
                        testCase.getCaseCode(),
                        testCase.getCaseName(),
                        item.getSortOrder(),
                        item.getItemStatus(),
                        null,
                        true
                );
            }

            // Build while the input entity is initialized; HTTP still happens outside this transaction.
            // Guard: if requestPath still has unresolved {variable} placeholders, mark ERROR early.
            String requestPath = input.getRequestPath();
            if (requestPath != null && requestPath.matches(".*\\{[^}]+}.*")) {
                log.warn("[prepareItemExecution] Unresolved path variable in requestPath='{}' for item id={}",
                        requestPath, item.getId());
                return new ItemExecutionContext(
                        item.getId(),
                        testCase.getId(),
                        testCase.getCaseCode(),
                        testCase.getCaseName(),
                        item.getSortOrder(),
                        item.getItemStatus(),
                        null,
                        true  // treat as missingInput so caller saves PREPARE_FAILED error
                );
            }
            PreparedHttpRequestResponse prepared = testRequestBuilder.build(baseUrl, input);
            return new ItemExecutionContext(
                    item.getId(),
                    testCase.getId(),
                    testCase.getCaseCode(),
                    testCase.getCaseName(),
                    item.getSortOrder(),
                    item.getItemStatus(),
                    prepared,
                    false
            );
        });
    }

    private void markItemStatus(UUID itemId, ExecutionStatus itemStatus) {
        transactionTemplate.executeWithoutResult(status -> {
            TestRunItem item = testRunItemRepository.findById(itemId)
                    .orElseThrow(() -> new ResourceNotFoundException("TestRunItem not found with id: " + itemId));
            item.setItemStatus(itemStatus);
            testRunItemRepository.save(item);
        });
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
    private void saveErrorResult(UUID itemId, Integer statusCode, String errorMessage) {
        HttpActualResponseDto errResponse = HttpActualResponseDto.builder()
                .statusCode(statusCode != null ? statusCode : 0)
                .responseBody(null)
                .responseTimeMs(0L)
                .errorMessage(errorMessage)
                .build();

        transactionTemplate.executeWithoutResult(status -> {
            TestRunItem item = testRunItemRepository.findById(itemId)
                    .orElseThrow(() -> new ResourceNotFoundException("TestRunItem not found with id: " + itemId));

            // 1. Save raw result with error message
            TestResult rawTestResult = testResultService.saveRawTestResult(item, errResponse);

            // 2. We can directly set ERROR to TestResult and item status since it's a pre-HTTP execution error
            rawTestResult.setResultStatus(ResultStatus.ERROR);
            testResultRepository.save(rawTestResult);
            // Note: RuleEngineService won't be called here as we don't have enough data
        });
    }

    private RealtimeItemSnapshot loadRealtimeItemSnapshot(UUID itemId) {
        return transactionTemplate.execute(status -> {
            TestRunItem item = testRunItemRepository.findById(itemId)
                    .orElseThrow(() -> new ResourceNotFoundException("TestRunItem not found with id: " + itemId));

            TestCase testCase = item.getTestCase();
            TestResult result = item.getTestResult();

            return new RealtimeItemSnapshot(
                    item.getId(),
                    testCase != null ? testCase.getId() : null,
                    testCase != null ? testCase.getCaseCode() : null,
                    testCase != null ? testCase.getCaseName() : null,
                    item.getSortOrder(),
                    item.getItemStatus(),
                    result != null ? result.getResultStatus() : null,
                    result != null ? result.getActualStatus() : null,
                    result != null ? result.getResponseTimeMs() : null,
                    result != null ? result.getErrorMessage() : null,
                    result != null ? result.getBlockedReason() : null,
                    result != null ? result.getActualResponseJson() : null
            );
        });
    }

    private TestRunRealtimeEvent buildRunRealtimeEvent(
            ExecutionContext context,
            RunStatus runStatus,
            RealtimeExecutionCounters counters) {

        return TestRunRealtimeEvent.builder()
                .projectId(context.projectId())
                .testRunId(context.runId())
                .runStatus(enumToString(runStatus))
                .totalItems(counters.totalItems)
                .completedItems(counters.completedItems)
                .successItems(counters.successItems)
                .failedItems(counters.failedItems)
                .errorItems(counters.errorItems)
                .build();
    }

    private TestRunRealtimeEvent buildItemStartedRealtimeEvent(
            ExecutionContext context,
            ItemExecutionContext itemContext,
            RealtimeExecutionCounters counters) {

        return TestRunRealtimeEvent.builder()
                .projectId(context.projectId())
                .testRunId(context.runId())
                .testRunItemId(itemContext.itemId())
                .testCaseId(itemContext.testCaseId())
                .runStatus(enumToString(RunStatus.RUNNING))
                .itemStatus(enumToString(itemContext.itemStatus()))
                .caseCode(itemContext.caseCode())
                .caseName(itemContext.caseName())
                .sortOrder(itemContext.sortOrder())
                .totalItems(counters.totalItems)
                .completedItems(counters.completedItems)
                .successItems(counters.successItems)
                .failedItems(counters.failedItems)
                .errorItems(counters.errorItems)
                .build();
    }

    private TestRunRealtimeEvent buildItemCompletedRealtimeEvent(
            ExecutionContext context,
            RealtimeItemSnapshot snapshot,
            RealtimeExecutionCounters counters) {

        return TestRunRealtimeEvent.builder()
                .projectId(context.projectId())
                .testRunId(context.runId())
                .testRunItemId(snapshot.itemId())
                .testCaseId(snapshot.testCaseId())
                .runStatus(enumToString(RunStatus.RUNNING))
                .itemStatus(enumToString(snapshot.itemStatus()))
                .resultStatus(enumToString(snapshot.resultStatus()))
                .caseCode(snapshot.caseCode())
                .caseName(snapshot.caseName())
                .sortOrder(snapshot.sortOrder())
                .actualStatus(snapshot.actualStatus())
                .responseTimeMs(snapshot.responseTimeMs())
                .errorMessage(snapshot.errorMessage())
                .blockedReason(snapshot.blockedReason())
                .actualResponseJson(snapshot.actualResponseJson())
                .totalItems(counters.totalItems)
                .completedItems(counters.completedItems)
                .successItems(counters.successItems)
                .failedItems(counters.failedItems)
                .errorItems(counters.errorItems)
                .build();
    }

    private String enumToString(Enum<?> value) {
        return value != null ? value.name() : null;
    }

    private record ExecutionContext(
            UUID runId,
            UUID projectId,
            String baseUrl,
            List<UUID> itemIds,
            int totalItems) {
    }

    private record ItemExecutionContext(
            UUID itemId,
            UUID testCaseId,
            String caseCode,
            String caseName,
            Integer sortOrder,
            ExecutionStatus itemStatus,
            PreparedHttpRequestResponse preparedRequest,
            boolean missingInput) {
    }

    private static final class RealtimeExecutionCounters {
        private final int totalItems;
        private int completedItems;
        private int successItems;
        private int failedItems;
        private int errorItems;

        private RealtimeExecutionCounters(int totalItems) {
            this.totalItems = totalItems;
        }

        private void markCompleted(ResultStatus resultStatus) {
            completedItems++;

            if (resultStatus == ResultStatus.PASS) {
                successItems++;
            } else if (resultStatus == ResultStatus.ERROR) {
                errorItems++;
            } else {
                failedItems++;
            }
        }
    }

    private record RealtimeItemSnapshot(
            UUID itemId,
            UUID testCaseId,
            String caseCode,
            String caseName,
            Integer sortOrder,
            ExecutionStatus itemStatus,
            ResultStatus resultStatus,
            Integer actualStatus,
            Integer responseTimeMs,
            String errorMessage,
            String blockedReason,
            String actualResponseJson) {
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
                .responseHeaders(executed.responseHeaders())
                .build();
    }

    private String truncateSafe(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private boolean performPreflightCheck(TestRun testRun, List<TestRunItem> items) {
        List<TestRunItem> safeItems = items.stream()
                .filter(item -> {
                    TestCase testCase = item.getTestCase();
                    if (testCase == null || testCase.getTestCaseInput() == null) return false;
                    String method = testCase.getTestCaseInput().getHttpMethod().name();
                    return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
                })
                .toList();

        if (safeItems.isEmpty()) {
            testRun.setPreflightStatus("SKIPPED");
            testRun.setPreflightSummary("No safe GET/HEAD test cases selected for preflight.");
            return true;
        }

        boolean all404 = true;
        for (TestRunItem item : safeItems) {
            TestCaseInput input = item.getTestCase().getTestCaseInput();
            try {
                PreparedHttpRequestResponse prepared = testRequestBuilder.build(testRun.getBaseUrl(), input);
                ExecutedHttpResponse executed = testHttpExecutor.execute(prepared);
                if (executed.statusCode() != 404) {
                    all404 = false;
                    break;
                }
            } catch (Exception e) {
                all404 = false;
                break;
            }
        }

        if (all404) {
            String error = "Base URL/runtime does not match uploaded source. All selected safe endpoints returned 404.";
            testRun.setRunStatus(RunStatus.FAILED);
            testRun.setPreflightStatus("FAILED");
            testRun.setPreflightSummary(error);
            String desc = testRun.getDescription();
            testRun.setDescription(desc == null ? error : desc + "\n\nPreflight Error: " + error);
            testRunRepository.save(testRun);
            return false;
        }
        testRun.setPreflightStatus("PASSED");
        testRun.setPreflightSummary("At least one safe preflight endpoint did not return 404.");
        testRunRepository.save(testRun);
        return true;
    }

    private void publishTestRunNotification(TestRun testRun) {
        if (testRun == null || testRun.getSourceProject() == null || testRun.getSourceProject().getId() == null) {
            return;
        }

        UUID projectId = testRun.getSourceProject().getId();
        boolean success = testRun.getRunStatus() == RunStatus.COMPLETED;
        notificationEventPublisher.publishForProjectOwner(
                projectId,
                success ? NotificationType.TEST_RUN_COMPLETED : NotificationType.TEST_RUN_FAILED,
                success ? NotificationSeverity.SUCCESS : NotificationSeverity.ERROR,
                success ? "Test run completed" : "Test run failed",
                success ? "Test run completed successfully." : "Test run completed with failures.",
                "/source-projects/" + projectId + "/test-runs/" + testRun.getId(),
                Map.of(
                        "projectId", projectId,
                        "testRunId", testRun.getId(),
                        "status", testRun.getRunStatus() == null ? "" : testRun.getRunStatus().name()
                ));
    }

    @Override
    @Transactional
    public TestRunDetailResponse getById(UUID id) {
        if (id == null) {
            throw new BadRequestException("id is required");
        }

        markStaleRunsFailed();

        TestRun testRun = testRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "TestRun not found with id: " + id));
        projectAccessService.requireCanViewProject(testRun.getSourceProject().getId());

        List<TestRunItem> items = testRunItemRepository
                .findByTestRun_IdOrderBySortOrderAsc(id);

        return toDetailResponse(testRun, items, Map.of());
    }

    @Override
    @Transactional
    public List<TestRunResponse> getByProjectId(UUID projectId) {
        if (projectId == null) {
            throw new BadRequestException("projectId is required");
        }

        projectAccessService.requireCanViewProject(projectId);
        markStaleRunsFailed();

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
                .runtimeMode(run.getRuntimeMode())
                .sourceRuntimeId(run.getSourceRuntime() == null ? null : run.getSourceRuntime().getId())
                .targetBaseUrlUsed(run.getTargetBaseUrlUsed())
                .runtimeStatusAtStart(run.getRuntimeStatusAtStart())
                .preflightStatus(run.getPreflightStatus())
                .preflightSummary(run.getPreflightSummary())
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
                .runtimeMode(run.getRuntimeMode())
                .sourceRuntimeId(run.getSourceRuntime() == null ? null : run.getSourceRuntime().getId())
                .targetBaseUrlUsed(run.getTargetBaseUrlUsed())
                .runtimeStatusAtStart(run.getRuntimeStatusAtStart())
                .preflightStatus(run.getPreflightStatus())
                .preflightSummary(run.getPreflightSummary())
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

        TestFailureAnalysis failureAnalysis = testFailureAnalysisRepository
                .findFirstByTestResult_IdOrderByCreatedAtDesc(result.getId())
                .orElse(null);

        return TestResultResponse.builder()
                .id(result.getId())
                .testRunItemId(result.getTestRunItem().getId())
                .actualStatus(result.getActualStatus())
                .resultStatus(result.getResultStatus())
                .responseTimeMs(result.getResponseTimeMs())
                .actualResponseJson(actualResponseJson)
                .errorMessage(result.getErrorMessage())
                .blockedReason(result.getBlockedReason())
                .failureAnalysis(toFailureAnalysisResponse(failureAnalysis))
                .createdAt(result.getCreatedAt())
                .updatedAt(result.getUpdatedAt())
                .build();
    }

    private TestFailureAnalysisDetailResponse toFailureAnalysisResponse(TestFailureAnalysis tfa) {
        if (tfa == null) {
            return null;
        }
        return TestFailureAnalysisDetailResponse.builder()
                .id(tfa.getId())
                .testResultId(tfa.getTestResult().getId())
                .aiJobLogId(tfa.getAiJobLog() == null ? null : tfa.getAiJobLog().getId())
                .modelName(tfa.getModelName())
                .failureType(tfa.getFailureType())
                .summary(tfa.getSummary())
                .rootCause(tfa.getRootCause())
                .expectedBehavior(tfa.getExpectedBehavior())
                .actualBehavior(tfa.getActualBehavior())
                .isLikelyBackendBug(tfa.getIsLikelyBackendBug())
                .isLikelyTestCaseBug(tfa.getIsLikelyTestCaseBug())
                .suggestedFixesJson(tfa.getSuggestedFixesJson())
                .recommendedNextAction(tfa.getRecommendedNextAction())
                .confidence(tfa.getConfidence())
                .priority(tfa.getPriority())
                .createdAt(tfa.getCreatedAt())
                .updatedAt(tfa.getUpdatedAt())
                .build();
    }

    @Transactional
    public int markStaleRunsFailed() {
        if (!testRunStaleProperties.isEnabled()) {
            return 0;
        }

        int updated = 0;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime pendingCutoff = now.minusMinutes(testRunStaleProperties.getPendingTimeoutMinutes());
        LocalDateTime runningCutoff = now.minusMinutes(testRunStaleProperties.getRunningTimeoutMinutes());

        List<TestRun> stalePending = testRunRepository.findByRunStatusAndCreatedAtBefore(
                RunStatus.PENDING, pendingCutoff);
        for (TestRun run : stalePending) {
            markRunFailed(run, STALE_PENDING_REASON);
            updated++;
        }

        List<TestRun> staleRunning = testRunRepository.findByRunStatusAndCreatedAtBefore(
                RunStatus.RUNNING, runningCutoff);
        for (TestRun run : staleRunning) {
            markRunFailed(run, STALE_RUNNING_REASON);
            updated++;
        }

        return updated;
    }

    private boolean failStaleRunIfNeeded(TestRun testRun) {
        if (testRun == null || !testRunStaleProperties.isEnabled()) {
            return false;
        }
        if (!isStale(testRun)) {
            return false;
        }
        markRunFailed(testRun, resolveStaleReason(testRun));
        return true;
    }

    private boolean isStale(TestRun testRun) {
        if (testRun.getCreatedAt() == null || testRun.getRunStatus() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (testRun.getRunStatus() == RunStatus.PENDING) {
            return testRun.getCreatedAt().isBefore(now.minusMinutes(testRunStaleProperties.getPendingTimeoutMinutes()));
        }
        if (testRun.getRunStatus() == RunStatus.RUNNING) {
            return testRun.getCreatedAt().isBefore(now.minusMinutes(testRunStaleProperties.getRunningTimeoutMinutes()));
        }
        return false;
    }

    private void markRunFailed(TestRun testRun, String reason) {
        if (testRun == null || testRun.getRunStatus() == RunStatus.FAILED) {
            return;
        }
        testRun.setRunStatus(RunStatus.FAILED);
        appendDescriptionIfMissing(testRun, reason);
        testRunRepository.save(testRun);
    }

    private String resolveStaleReason(TestRun testRun) {
        if (testRun != null && testRun.getRunStatus() == RunStatus.RUNNING) {
            return STALE_RUNNING_REASON;
        }
        return STALE_PENDING_REASON;
    }

    private void appendDescriptionIfMissing(TestRun testRun, String reason) {
        if (!hasText(reason)) {
            return;
        }
        String existing = testRun.getDescription();
        if (existing != null && existing.contains(reason)) {
            return;
        }
        testRun.setDescription(existing == null ? reason : existing + "\n\n" + reason);
    }

    private RuntimeMode resolveRuntimeMode(RuntimeMode requestedRuntimeMode) {
        return requestedRuntimeMode == null ? RuntimeMode.EXTERNAL_BASE_URL : requestedRuntimeMode;
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

    /**
     * Resolves and validates the effective base URL for a TestRun.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If {@code requestBaseUrl} has text: use it (validated).
     *   <li>Else if {@code projectDefaultTargetBaseUrl} has text: use it (validated).
     *   <li>Else throw BadRequestException with clear guidance.
     * </ol>
     *
     * <p><strong>Important:</strong> {@code projectDefaultTargetBaseUrl} comes from
     * {@code SourceProject.defaultTargetBaseUrl}, which is the live application endpoint.
     * {@code SourceProject.repositoryUrl} (GitHub source URL) must NEVER be used here.
     *
     * @param requestBaseUrl             baseUrl from the incoming request (may be null/blank)
     * @param projectDefaultTargetBaseUrl project-level runtime target (may be null)
     */
    private String resolveBaseUrl(String requestBaseUrl, String projectDefaultTargetBaseUrl) {
        if (hasText(requestBaseUrl)) {
            String trimmed = requestBaseUrl.trim();
            validateBaseUrl(trimmed);
            if (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed;
        }

        if (hasText(projectDefaultTargetBaseUrl)) {
            String trimmed = projectDefaultTargetBaseUrl.trim();
            validateBaseUrl(trimmed);
            if (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            log.info("[TestRunService] No baseUrl in request — using project defaultTargetBaseUrl: {}", trimmed);
            return trimmed;
        }

        throw new BadRequestException(
                "Test run baseUrl is required. " +
                "Provide baseUrl in the request, or configure project defaultTargetBaseUrl.");
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

    private String getSkippedReason(TestRunItem item, ExecutionMode executionMode) {
        if (item == null) {
            return null;
        }
        TestCase testCase = item.getTestCase();
        if (testCase == null) {
            return null;
        }
        TestCaseInput input = testCase.getTestCaseInput();
        if (input == null) {
            return null;
        }

        com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod method = input.getHttpMethod();
        boolean isMutating = method != com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.GET && 
                             method != com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.HEAD;

        if (executionMode == ExecutionMode.READ_ONLY) {
            if (isMutating) {
                return "Skipped in READ_ONLY mode: mutating request is not allowed.";
            }
            if (Boolean.TRUE.equals(testCase.getRequiresWrite())) {
                return "Skipped in READ_ONLY mode: mutating request (requiresWrite) is not allowed.";
            }
        }

        // B. Also block unsafe positive GET-by-ID cases when they rely on fake hardcoded path data.
        if (method == com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod.GET && 
            testCase.getCaseType() == com.aitoolcheck.ai_toolcheck1_backend.enums.CaseType.POSITIVE) {
            
            String path = input.getRequestPath();
            if (path != null) {
                String upperPath = path.toUpperCase();
                if (upperPath.contains("CUSTOMER_ABC_123") ||
                    upperPath.contains("UNKNOWN_") ||
                    upperPath.contains("SAMPLE_") ||
                    upperPath.contains("TEST_") ||
                    upperPath.contains("FAKE_") ||
                    upperPath.contains("DUMMY_")) {
                    return "Skipped: positive path-variable test requires real test data.";
                }
            }
        }

        return null;
    }

    private void saveSkippedResult(UUID itemId, String reason) {
        HttpActualResponseDto skipResponse = HttpActualResponseDto.builder()
                .statusCode(0)
                .responseBody(null)
                .responseTimeMs(0L)
                .errorMessage(reason)
                .build();

        transactionTemplate.executeWithoutResult(status -> {
            TestRunItem item = testRunItemRepository.findById(itemId)
                    .orElseThrow(() -> new ResourceNotFoundException("TestRunItem not found with id: " + itemId));

            TestResult rawTestResult = testResultService.saveRawTestResult(item, skipResponse);
            rawTestResult.setResultStatus(ResultStatus.SKIPPED);
            rawTestResult.setBlockedReason(reason);
            testResultRepository.save(rawTestResult);
        });
    }
}
