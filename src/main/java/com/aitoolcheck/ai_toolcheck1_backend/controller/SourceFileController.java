package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceFileService;
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
@RequestMapping("/v1/source-files")
@RequiredArgsConstructor
@Tag(name = "Source Files", description = "Source file lookup APIs")
public class SourceFileController {

    private final SourceFileService sourceFileService;

    @GetMapping("/project/{projectId}")
    @Operation(
            summary = "Get source files by project",
            description = "Get all source files associated with a source project.",
            operationId = "getSourceFilesByProject"
    )
    public ResponseEntity<List<SourceFileResponse>> getByProjectId(@PathVariable UUID projectId) {
        return ResponseEntity.ok(sourceFileService.getByProjectId(projectId));
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get source file by id",
            description = "Get source file detail by UUID.",
            operationId = "getSourceFileById"
    )
    public ResponseEntity<SourceFileDetailResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(sourceFileService.getById(id));
    }
}
