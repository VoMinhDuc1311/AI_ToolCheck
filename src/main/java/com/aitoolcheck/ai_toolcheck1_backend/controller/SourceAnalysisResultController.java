package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceanalysisresult.res.SourceAnalysisResultDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceAnalysisResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class SourceAnalysisResultController {

    private final SourceAnalysisResultService sourceAnalysisResultService;

    @PostMapping("/source-projects/{projectId}/analyze-source")
    public ResponseEntity<SourceAnalysisResultDetailResponse> analyzeProject(@PathVariable UUID projectId) {
        return ResponseEntity.ok(sourceAnalysisResultService.analyzeProject(projectId));
    }

    @GetMapping("/source-analysis-results/project/{projectId}")
    public ResponseEntity<SourceAnalysisResultDetailResponse> getByProjectId(@PathVariable UUID projectId) {
        return ResponseEntity.ok(sourceAnalysisResultService.getByProjectId(projectId));
    }
}