package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.CreateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.PermanentDeleteProjectRequest;
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
@Tag(name = "Source Projects", description = "Source project creation, lookup, update, archive, restore, and deletion APIs")
public class SourceProjectController {

        private final SourceProjectService sourceProjectService;
        private final SourceFileService sourceFileService;
        private final SourceAnalysisResultService sourceAnalysisResultService;

        @PostMapping
        @Operation(summary = "Create source project", description = "Create a new source project.", operationId = "createSourceProject")
        public ResponseEntity<SourceProjectDetailResponse> create(
                        @Valid @RequestBody CreateSourceProjectRequest request) {
                SourceProjectDetailResponse response = sourceProjectService.create(request);
                return new ResponseEntity<>(response, HttpStatus.CREATED);
        }

        @GetMapping("/{id}")
        @Operation(summary = "Get source project by id", description = "Get source project detail by UUID. Authorized owner/admin/member can view archived projects.", operationId = "getSourceProjectById")
        public ResponseEntity<SourceProjectDetailResponse> getById(@PathVariable UUID id) {
                SourceProjectDetailResponse response = sourceProjectService.getById(id);
                return ResponseEntity.ok(response);
        }

        @GetMapping
        @Operation(summary = "Get all source projects", description = "Get source projects visible to the current user. Excludes archived by default.", operationId = "getAllSourceProjects")
        public ResponseEntity<List<SourceProjectResponse>> getAll(
                        @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
                List<SourceProjectResponse> response = sourceProjectService.getAll(includeArchived);
                return ResponseEntity.ok(response);
        }

        @GetMapping("/my")
        @Operation(summary = "Get my source projects", description = "Get source projects owned by or shared with the current user. Excludes archived by default.", operationId = "getMySourceProjects")
        public ResponseEntity<List<SourceProjectResponse>> getMine(
                        @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
                List<SourceProjectResponse> response = sourceProjectService.getMine(includeArchived);
                return ResponseEntity.ok(response);
        }

        @GetMapping("/public")
        @Operation(summary = "Get public source projects", description = "Get source projects published as public read-only. Archived projects are excluded.", operationId = "getPublicSourceProjects")
        public ResponseEntity<List<SourceProjectResponse>> getPublic() {
                List<SourceProjectResponse> response = sourceProjectService.getPublic();
                return ResponseEntity.ok(response);
        }

        @GetMapping("/archived")
        @Operation(summary = "Get archived source projects", description = "Get projects that have been archived. Owner and ADMIN only. Members see projects they belong to.", operationId = "getArchivedSourceProjects")
        public ResponseEntity<ApiResponse<List<SourceProjectResponse>>> getArchived() {
                List<SourceProjectResponse> data = sourceProjectService.getArchived();
                return ResponseEntity.ok(success("Archived projects fetched successfully.", data));
        }

        @PostMapping(value = "/{projectId}/upload-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
                @Operation(summary = "Upload source ZIP", description = "Upload a source ZIP archive for a source project.", operationId = "uploadSourceZip")
        public ResponseEntity<SourceFileUploadResponse> uploadZip(
                        @PathVariable UUID projectId,
                        @RequestPart("file") MultipartFile file) {
                SourceFileUploadResponse response = sourceFileService.uploadZip(projectId, file);
                return ResponseEntity.ok(response);
        }

        @PostMapping("/{projectId}/analyze-source")
        @Operation(summary = "Analyze source project", description = "Analyze the source files for a source project.", operationId = "analyzeSourceProject")
        public ResponseEntity<SourceAnalysisResultDetailResponse> analyzeProject(@PathVariable UUID projectId) {
                return ResponseEntity.ok(sourceAnalysisResultService.analyzeProject(projectId));
        }

        @PutMapping("/{id}")
        @Operation(summary = "Update source project", description = "Update source project metadata.", operationId = "updateSourceProject")
        public ResponseEntity<SourceProjectDetailResponse> update(
                        @PathVariable UUID id,
                        @Valid @RequestBody UpdateSourceProjectRequest request) {
                SourceProjectDetailResponse response = sourceProjectService.update(id, request);
                return ResponseEntity.ok(response);
        }

        @PatchMapping("/{id}/visibility")
        @Operation(summary = "Update source project visibility", description = "Update project visibility. Owner or ADMIN only.", operationId = "updateSourceProjectVisibility")
        public ResponseEntity<ApiResponse<SourceProjectDetailResponse>> updateVisibility(
                        @PathVariable UUID id,
                        @Valid @RequestBody UpdateProjectVisibilityRequest request) {
                SourceProjectDetailResponse response = sourceProjectService.updateVisibility(id, request);
                return ResponseEntity.ok(success("Project visibility updated successfully.", response));
        }

        @PatchMapping("/{id}/archive")
        @Operation(summary = "Archive source project", description = "Archive a project: hides it from default lists, preserves all child data. OWNER or ADMIN only.", operationId = "archiveSourceProject")
        public ResponseEntity<ApiResponse<SourceProjectDetailResponse>> archive(@PathVariable UUID id) {
                SourceProjectDetailResponse response = sourceProjectService.archiveProject(id);
                return ResponseEntity.ok(success("Project archived successfully.", response));
        }

        @PatchMapping("/{id}/restore")
        @Operation(summary = "Restore archived source project", description = "Restore an archived project back to the active list. OWNER or ADMIN only.", operationId = "restoreSourceProject")
        public ResponseEntity<ApiResponse<SourceProjectDetailResponse>> restore(@PathVariable UUID id) {
                SourceProjectDetailResponse response = sourceProjectService.restoreProject(id);
                return ResponseEntity.ok(success("Project restored successfully.", response));
        }

        @DeleteMapping("/{id}")
        @Operation(summary = "Archive source project (safe delete)", description = "Archives the project — hides it from default lists but preserves all data. Use DELETE /{id}/permanent for irreversible removal. OWNER or ADMIN only.", operationId = "deleteSourceProject")
        public ResponseEntity<ApiResponse<SourceProjectDetailResponse>> delete(@PathVariable UUID id) {
                SourceProjectDetailResponse response = sourceProjectService.archiveProject(id);
                return ResponseEntity.ok(success("Project archived successfully. All data preserved.", response));
        }

        @DeleteMapping("/{id}/permanent")
        @Operation(summary = "Permanently delete source project", description = "Irreversibly deletes the project and ALL child data (test runs, AI jobs, source files, etc.). Requires confirmProjectKey to match the project key. OWNER or ADMIN only. Cannot be undone.", operationId = "permanentlyDeleteSourceProject")
        public ResponseEntity<ApiResponse<Void>> permanentDelete(
                        @PathVariable UUID id,
                        @Valid @RequestBody PermanentDeleteProjectRequest request) {
                sourceProjectService.permanentlyDeleteProject(id, request);
                return ResponseEntity.ok(ApiResponse.<Void>builder()
                                .code("SUCCESS")
                                .message("Project and all associated data have been permanently deleted.")
                                .data(null)
                                .timestamp(LocalDateTime.now())
                                .build());
        }

        private <T> ApiResponse<T> success(String message, T data) {
                return ApiResponse.<T>builder()
                                .code("SUCCESS")
                                .message(message)
                                .data(data)
                                .timestamp(LocalDateTime.now())
                                .build();
        }
}
