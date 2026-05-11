package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req.CreateTestRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req.ExecuteTestRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.TestRunDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.TestRunResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/test-runs")
@RequiredArgsConstructor
@Tag(name = "Test Runs", description = "Test runner frame APIs")
public class TestRunController {

        private final TestRunService testRunService;

        @PostMapping
        @Operation(summary = "Create test run", description = "Create a test run and generate test run items from selected active test cases.", operationId = "createTestRun")
        public ResponseEntity<ApiResponse<TestRunDetailResponse>> create(
                        @Valid @RequestBody CreateTestRunRequest request) {
                TestRunDetailResponse data = testRunService.create(request);

                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(success("Test run created successfully.", data));
        }

        @PostMapping("/execute")
        @Operation(summary = "Execute test run", description = "Initialize and execute a test run with selected test cases. This prepares the framework data before sending actual HTTP requests.", operationId = "executeTestRun")
        public ResponseEntity<ApiResponse<TestRunDetailResponse>> execute(
                        @Valid @RequestBody ExecuteTestRunRequest request) {
                TestRunDetailResponse data = testRunService.createTestRun(request);

                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(success("Test run initialized successfully.", data));
        }

        @GetMapping("/project/{projectId}")
        @Operation(summary = "List test runs by project", description = "List test runs for a source project ordered by newest first.", operationId = "listTestRunsByProject")
        public ResponseEntity<ApiResponse<List<TestRunResponse>>> getByProjectId(
                        @PathVariable UUID projectId) {
                List<TestRunResponse> data = testRunService.getByProjectId(projectId);

                return ResponseEntity.ok(success("Test runs fetched successfully.", data));
        }

        @GetMapping("/{id}")
        @Operation(summary = "Get test run detail", description = "Get test run detail including generated test run items.", operationId = "getTestRunById")
        public ResponseEntity<ApiResponse<TestRunDetailResponse>> getById(
                        @PathVariable UUID id) {
                TestRunDetailResponse data = testRunService.getById(id);

                return ResponseEntity.ok(success("Test run fetched successfully.", data));
        }

        @PostMapping("/{id}/prepare")
        @Operation(summary = "Prepare test run requests", description = "Build prepared HTTP request objects for each test run item without executing them.", operationId = "prepareTestRun")
        public ResponseEntity<ApiResponse<TestRunDetailResponse>> prepare(
                        @PathVariable UUID id) {
                TestRunDetailResponse data = testRunService.prepare(id);

                return ResponseEntity.ok(success("Test run requests prepared successfully.", data));
        }

        @PostMapping("/{id}/execute")
        @Operation(
                summary = "Execute test run",
                description = "Synchronously execute all items in a test run against the target API. "
                        + "Persists TestResult per item and aggregates run status. "
                        + "Requires MAINTAINER or EDITOR role for READ_ONLY mode; "
                        + "MAINTAINER only for SAFE_WRITE or FULL_WRITE mode.",
                operationId = "executeTestRunById"
        )
        public ResponseEntity<ApiResponse<TestRunDetailResponse>> executeById(
                        @PathVariable UUID id) {
                TestRunDetailResponse data = testRunService.execute(id);

                return ResponseEntity.ok(success("Test run executed successfully.", data));
        }

        private <T> ApiResponse<T> success(String message, T data) {
                return ApiResponse.<T>builder()
                                .code("SUCCESS")
                                .message(message)
                                .data(data)
                                .timestamp(LocalDateTime.now())
                                .build();
        }
}