package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.PagedResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.testcase.res.TestCaseDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestCaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects")
@RequiredArgsConstructor
@Tag(name = "Project Test Cases", description = "Endpoints for project test case search & pagination")
public class ProjectTestCaseController {

    private final TestCaseService testCaseService;

    @GetMapping("/{projectId}/test-cases")
    @Operation(summary = "Search test cases with pagination and filters", operationId = "searchTestCases")
    public ResponseEntity<ApiResponse<PagedResponse<TestCaseDetailResponse>>> search(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String caseType,
            @RequestParam(required = false) String priorityLevel,
            @RequestParam(required = false) String generatedBy,
            @RequestParam(required = false) Boolean activeFlag,
            @RequestParam(required = false) Boolean requiresWrite,
            @RequestParam(required = false) Boolean cleanupRequired,
            @RequestParam(required = false) String httpMethod,
            @RequestParam(required = false) String endpointPath,
            @RequestParam(required = false) UUID apiEndpointId,
            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo,
            @RequestParam(required = false) String updatedFrom,
            @RequestParam(required = false) String updatedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        PagedResponse<TestCaseDetailResponse> data = testCaseService.searchTestCases(
                projectId,
                keyword,
                caseType,
                priorityLevel,
                generatedBy,
                activeFlag,
                requiresWrite,
                cleanupRequired,
                httpMethod,
                endpointPath,
                apiEndpointId,
                createdFrom,
                createdTo,
                updatedFrom,
                updatedTo,
                page,
                size,
                sortBy,
                sortDir
        );

        return ResponseEntity.ok(ApiResponse.<PagedResponse<TestCaseDetailResponse>>builder()
                .code("SUCCESS")
                .message("Test cases search results fetched successfully.")
                .data(data)
                .timestamp(LocalDateTime.now())
                .build());
    }
}
