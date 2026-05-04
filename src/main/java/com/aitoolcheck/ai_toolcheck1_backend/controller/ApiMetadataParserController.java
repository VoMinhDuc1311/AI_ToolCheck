package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apimetadata.res.ApiMetadataParseResultResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiMetadataParserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ApiMetadataParserController {

    private final ApiMetadataParserService apiMetadataParserService;

    @PostMapping("/source-projects/{projectId}/parse-api-metadata")
    public ResponseEntity<ApiMetadataParseResultResponse> parseApiMetadata(@PathVariable UUID projectId) {
        return ResponseEntity.ok(apiMetadataParserService.parseProject(projectId));
    }
}
