package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceanalysisresult.res.SourceAnalysisResultDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceAnalysisResultService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequestMapping("/v1/source-analysis-results")
@RequiredArgsConstructor
@Tag(name = "Source Analysis", description = "Source analysis result lookup APIs")
public class SourceAnalysisResultController {

    private final SourceAnalysisResultService sourceAnalysisResultService;

    @GetMapping("/project/{projectId}")
    @Operation(
            summary = "Get source analysis by project",
            description = "Get source analysis result detail for a source project.",
            operationId = "getSourceAnalysisByProject"
    )
    public ResponseEntity<SourceAnalysisResultDetailResponse> getByProjectId(@PathVariable UUID projectId) {
        return ResponseEntity.ok(sourceAnalysisResultService.getByProjectId(projectId));
    }
}
