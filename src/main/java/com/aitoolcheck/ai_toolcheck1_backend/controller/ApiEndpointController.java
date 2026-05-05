package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.res.ApiEndpointDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.res.ApiEndpointResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiEndpointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "API Endpoints", description = "Parsed API endpoint lookup APIs")
public class ApiEndpointController {

    private final ApiEndpointService apiEndpointService;

    @GetMapping("/api-endpoints/project/{projectId}")
    @Operation(
            summary = "Get endpoints by project",
            description = "Get parsed API endpoints associated with a source project.",
            operationId = "getApiEndpointsByProject"
    )
    public ResponseEntity<List<ApiEndpointResponse>> getByProjectId(@PathVariable UUID projectId) {
        return ResponseEntity.ok(apiEndpointService.getByProjectId(projectId));
    }

    @GetMapping("/api-endpoints/{id}")
    @Operation(
            summary = "Get endpoint by id",
            description = "Get parsed API endpoint detail by UUID.",
            operationId = "getApiEndpointById"
    )
    public ResponseEntity<ApiEndpointDetailResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(apiEndpointService.getById(id));
    }
}
