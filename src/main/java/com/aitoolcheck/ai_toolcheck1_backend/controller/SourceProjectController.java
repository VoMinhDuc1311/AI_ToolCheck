package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.CreateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.UpdateProjectVisibilityRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.UpdateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceanalysisresult.res.SourceAnalysisResultDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileUploadResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceProjectDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceProjectResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceAnalysisResultService;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceFileService;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/source-projects")
@RequiredArgsConstructor
@Tag(name = "Source Projects", description = "Source project creation, lookup, update, and deletion APIs")
public class SourceProjectController {

    private final SourceProjectService sourceProjectService;
    private final SourceFileService sourceFileService;
    private final SourceAnalysisResultService sourceAnalysisResultService;

    @PostMapping
    @Operation(
            summary = "Create source project",
            description = "Create a new source project.",
            operationId = "createSourceProject"
    )
    public ResponseEntity<SourceProjectDetailResponse> create(@Valid @RequestBody CreateSourceProjectRequest request) {
        SourceProjectDetailResponse response = sourceProjectService.create(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get source project by id",
            description = "Get source project detail by UUID.",
            operationId = "getSourceProjectById"
    )
    public ResponseEntity<SourceProjectDetailResponse> getById(@PathVariable UUID id) {
        SourceProjectDetailResponse response = sourceProjectService.getById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Operation(
            summary = "Get all source projects",
            description = "Get all source projects.",
            operationId = "getAllSourceProjects"
    )
    public ResponseEntity<List<SourceProjectResponse>> getAll() {
        List<SourceProjectResponse> response = sourceProjectService.getAll();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/my")
    @Operation(
            summary = "Get my source projects",
            description = "Get source projects owned by or shared with the current user.",
            operationId = "getMySourceProjects"
    )
    public ResponseEntity<List<SourceProjectResponse>> getMine() {
        List<SourceProjectResponse> response = sourceProjectService.getMine();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/public")
    @Operation(
            summary = "Get public source projects",
            description = "Get source projects published as public read-only.",
            operationId = "getPublicSourceProjects"
    )
    public ResponseEntity<List<SourceProjectResponse>> getPublic() {
        List<SourceProjectResponse> response = sourceProjectService.getPublic();
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/{projectId}/upload-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Upload source ZIP",
            description = "Upload a source ZIP archive for a source project.",
            operationId = "uploadSourceZip"
    )
    public ResponseEntity<SourceFileUploadResponse> uploadZip(
            @PathVariable UUID projectId,
            @RequestPart("file") MultipartFile file
    ) {
        SourceFileUploadResponse response = sourceFileService.uploadZip(projectId, file);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{projectId}/analyze-source")
    @Operation(
            summary = "Analyze source project",
            description = "Analyze the source files for a source project.",
            operationId = "analyzeSourceProject"
    )
    public ResponseEntity<SourceAnalysisResultDetailResponse> analyzeProject(@PathVariable UUID projectId) {
        return ResponseEntity.ok(sourceAnalysisResultService.analyzeProject(projectId));
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Update source project",
            description = "Update source project metadata.",
            operationId = "updateSourceProject"
    )
    public ResponseEntity<SourceProjectDetailResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSourceProjectRequest request
    ) {
        SourceProjectDetailResponse response = sourceProjectService.update(id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/visibility")
    @Operation(
            summary = "Update source project visibility",
            description = "Update project visibility. Owner or ADMIN only.",
            operationId = "updateSourceProjectVisibility"
    )
    public ResponseEntity<ApiResponse<SourceProjectDetailResponse>> updateVisibility(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProjectVisibilityRequest request
    ) {
        SourceProjectDetailResponse response = sourceProjectService.updateVisibility(id, request);
        return ResponseEntity.ok(ApiResponse.<SourceProjectDetailResponse>builder()
                .code("SUCCESS")
                .message("Project visibility updated successfully.")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build());
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Delete source project",
            description = "Delete a source project by UUID.",
            operationId = "deleteSourceProject"
    )
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        sourceProjectService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
