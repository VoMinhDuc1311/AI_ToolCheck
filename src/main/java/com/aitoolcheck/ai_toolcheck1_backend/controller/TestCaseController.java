package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.MessageResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.CreateTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.GenerateTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.req.UpdateTestCaseRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.res.TestCaseDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.res.TestCaseResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestCaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/test-cases")
@RequiredArgsConstructor
@Tag(name = "Test Cases", description = "Test case CRUD APIs")
public class TestCaseController {

        private final TestCaseService testCaseService;

        @PostMapping
        @Operation(summary = "Create test case", description = "Create a complete test case with metadata, input and assertions.", operationId = "createTestCase")
        public ResponseEntity<ApiResponse<TestCaseDetailResponse>> create(
                @Valid @RequestBody CreateTestCaseRequest request) {
                TestCaseDetailResponse data = testCaseService.create(request);

                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(success("Test case created successfully.", data));
        }

        @GetMapping("/project/{projectId}")
        @Operation(summary = "List test cases by project", description = "List non-deleted test cases for a source project.", operationId = "listTestCasesByProject")
        public ResponseEntity<ApiResponse<List<TestCaseResponse>>> getByProjectId(
                @PathVariable UUID projectId) {
                List<TestCaseResponse> data = testCaseService.getByProjectId(projectId);

                return ResponseEntity.ok(success("Test cases fetched successfully.", data));
        }

        @GetMapping("/{id}")
        @Operation(summary = "Get test case detail", description = "Get complete test case detail including input and assertions.", operationId = "getTestCaseById")
        public ResponseEntity<ApiResponse<TestCaseDetailResponse>> getById(
                @PathVariable UUID id) {
                TestCaseDetailResponse data = testCaseService.getById(id);

                return ResponseEntity.ok(success("Test case fetched successfully.", data));
        }

        @PatchMapping("/{id}")
        @Operation(summary = "Update test case", description = "Update test case metadata, input and assertions.", operationId = "updateTestCase")
        public ResponseEntity<ApiResponse<TestCaseDetailResponse>> update(
                @PathVariable UUID id,
                @Valid @RequestBody UpdateTestCaseRequest request) {
                TestCaseDetailResponse data = testCaseService.update(id, request);

                return ResponseEntity.ok(success("Test case updated successfully.", data));
        }

        @DeleteMapping("/{id}")
        @Operation(summary = "Delete test case", description = "Soft delete a test case.", operationId = "deleteTestCase")
        public ResponseEntity<ApiResponse<MessageResponse>> delete(
                @PathVariable UUID id) {
                testCaseService.delete(id);

                return ResponseEntity.ok(success(
                        "Test case deleted successfully.",
                        MessageResponse.builder().message("Test case deleted successfully.").build()));
        }

        // --- ĐIỂM SỬA CHỮA (FIX) ---
        // Thêm mảng {"/generate", "/generate-async"} để hỗ trợ cả 2 URL, không làm hỏng code cũ của Dev A
        @PostMapping({"/generate", "/generate-async"})
        @Operation(summary = "Generate test cases via AI", description = "Triggers an asynchronous job to generate test cases for a specific API endpoint via RabbitMQ.", operationId = "generateTestCasesAsync")
        public ResponseEntity<ApiResponse<MessageResponse>> generateTestCaseAsync(
                @Valid @RequestBody GenerateTestCaseRequest request) {
                UUID jobId = testCaseService.generateTestCaseAsync(request);

                // Code cũ đang trả về HTTP Status 202 (ACCEPTED) và bọc jobId trong trường "message" của lớp MessageResponse
                return ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(success(
                                "Yêu cầu sinh Test Case bằng AI đã được tiếp nhận và đang xử lý ngầm.",
                                MessageResponse.builder().message(jobId.toString()).build()));
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