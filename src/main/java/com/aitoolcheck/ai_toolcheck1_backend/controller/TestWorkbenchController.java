package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testworkbench.res.TestWorkbenchResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestWorkbenchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/v1/source-projects")
@RequiredArgsConstructor
@Tag(name = "Test Workbench", description = "Test workbench APIs providing aggregate project test data")
public class TestWorkbenchController {

    private final TestWorkbenchService testWorkbenchService;

    @GetMapping("/{projectId}/test-workbench")
    @Operation(summary = "Get project test workbench data", description = "Get aggregate test data including cases, runs, and failure analytics.", operationId = "getTestWorkbenchData")
    public ResponseEntity<ApiResponse<TestWorkbenchResponse>> getTestWorkbenchData(
            @PathVariable UUID projectId) {
        TestWorkbenchResponse data = testWorkbenchService.getTestWorkbenchData(projectId);
        return ResponseEntity.ok(ApiResponse.<TestWorkbenchResponse>builder()
                .code("SUCCESS")
                .message("Test workbench data fetched successfully.")
                .data(data)
                .timestamp(LocalDateTime.now())
                .build());
    }
}
