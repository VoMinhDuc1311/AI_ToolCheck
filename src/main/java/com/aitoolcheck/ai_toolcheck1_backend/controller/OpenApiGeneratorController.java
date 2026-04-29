package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.openapi.res.OpenApiGenerateResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.OpenApiGeneratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/source-projects")
public class OpenApiGeneratorController {

    private final OpenApiGeneratorService openApiGeneratorService;

    // Read-only preview — does NOT write to DB
    @GetMapping("/{projectId}/openapi-json")
    public ResponseEntity<Map<String, Object>> previewOpenApiJson(@PathVariable UUID projectId) {
        return ResponseEntity.ok(openApiGeneratorService.generateOpenApiJson(projectId));
    }

    // Generate and persist as a new ApiDocumentVersion
    @PostMapping("/{projectId}/generate-openapi")
    public ResponseEntity<OpenApiGenerateResponse> generateAndSaveOpenApi(@PathVariable UUID projectId) {
        return ResponseEntity.ok(openApiGeneratorService.generateAndSaveOpenApi(projectId));
    }
}
