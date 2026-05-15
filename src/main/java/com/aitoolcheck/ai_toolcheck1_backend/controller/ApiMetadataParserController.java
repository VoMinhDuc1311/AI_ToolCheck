package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apimetadata.res.ApiMetadataParseResultResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiMetadataParserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequestMapping("/v1/source-projects")
@RequiredArgsConstructor
@Tag(name = "API Metadata Parser", description = "API metadata parsing APIs")
public class ApiMetadataParserController {

    private final ApiMetadataParserService apiMetadataParserService;

    @PostMapping("/{projectId}/parse-api-metadata")
    @Operation(
            summary = "Parse API metadata",
            description = "Parse API metadata from an analyzed source project.",
            operationId = "parseApiMetadata"
    )
    public ResponseEntity<ApiMetadataParseResultResponse> parseApiMetadata(@PathVariable UUID projectId) {
        return ResponseEntity.ok(apiMetadataParserService.parseProject(projectId));
    }
}
