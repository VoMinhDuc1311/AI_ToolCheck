package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.CreateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.UpdateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceProjectDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceProjectResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/source-projects")
@RequiredArgsConstructor
@Tag(name = "Source Projects", description = "Source project creation, lookup, update, and deletion APIs")
public class SourceProjectController {

    private final SourceProjectService sourceProjectService;

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
