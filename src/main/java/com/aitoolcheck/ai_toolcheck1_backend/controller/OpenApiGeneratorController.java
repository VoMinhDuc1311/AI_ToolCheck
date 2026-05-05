package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.openapi.res.OpenApiGenerateResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.OpenApiGeneratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/source-projects")
@Tag(name = "OpenAPI Generator", description = "OpenAPI document generation and preview APIs")
public class OpenApiGeneratorController {

    private final OpenApiGeneratorService openApiGeneratorService;

    // Read-only preview — does NOT write to DB
    @GetMapping("/{projectId}/openapi-json")
    @Operation(
            summary = "Preview OpenAPI JSON",
            description = "Generate a read-only OpenAPI JSON preview for a source project.",
            operationId = "previewOpenApiJson"
    )
    public ResponseEntity<Map<String, Object>> previewOpenApiJson(@PathVariable UUID projectId) {
        return ResponseEntity.ok(openApiGeneratorService.generateOpenApiJson(projectId));
    }

    // Generate and persist as a new ApiDocumentVersion
    @PostMapping("/{projectId}/generate-openapi")
    @Operation(
            summary = "Generate OpenAPI document",
            description = "Generate and persist an OpenAPI document version for a source project.",
            operationId = "generateOpenApiDocument"
    )
    public ResponseEntity<OpenApiGenerateResponse> generateAndSaveOpenApi(@PathVariable UUID projectId) {
        return ResponseEntity.ok(openApiGeneratorService.generateAndSaveOpenApi(projectId));
    }
}
