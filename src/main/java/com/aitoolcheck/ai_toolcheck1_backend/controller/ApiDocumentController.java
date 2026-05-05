package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.req.CreateApiDocumentRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.req.UpdateApiDocumentRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.res.ApiDocumentDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.res.ApiDocumentVersionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/api-documents")
@Tag(name = "API Documents", description = "API document lifecycle, publication, and version listing APIs")
public class ApiDocumentController {

    private final ApiDocumentService apiDocumentService;

    @PostMapping
    @Operation(
            summary = "Create API document",
            description = "Create an API document record for a source project.",
            operationId = "createApiDocument"
    )
    public ResponseEntity<ApiResponse<ApiDocumentDetailResponse>> create(
            @Valid @RequestBody CreateApiDocumentRequest request
    ) {
        ApiDocumentDetailResponse data = apiDocumentService.create(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(success("ApiDocument created successfully.", data));
    }

    @GetMapping("/project/{projectId}")
    @Operation(
            summary = "Get API document by project",
            description = "Get the API document associated with a source project.",
            operationId = "getApiDocumentByProject"
    )
    public ResponseEntity<ApiResponse<ApiDocumentDetailResponse>> getByProjectId(
            @PathVariable UUID projectId
    ) {
        ApiDocumentDetailResponse data = apiDocumentService.getByProjectId(projectId);

        return ResponseEntity.ok(success("ApiDocument fetched successfully.", data));
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get API document by id",
            description = "Get API document detail by UUID.",
            operationId = "getApiDocumentById"
    )
    public ResponseEntity<ApiResponse<ApiDocumentDetailResponse>> getById(
            @PathVariable UUID id
    ) {
        ApiDocumentDetailResponse data = apiDocumentService.getById(id);

        return ResponseEntity.ok(success("ApiDocument fetched successfully.", data));
    }

    @PatchMapping("/{id}")
    @Operation(
            summary = "Update API document",
            description = "Partially update API document metadata.",
            operationId = "updateApiDocument"
    )
    public ResponseEntity<ApiResponse<ApiDocumentDetailResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateApiDocumentRequest request
    ) {
        ApiDocumentDetailResponse data = apiDocumentService.update(id, request);

        return ResponseEntity.ok(success("ApiDocument updated successfully.", data));
    }

    @GetMapping("/{id}/versions")
    @Operation(
            summary = "Get API document versions",
            description = "Get all versions of an API document.",
            operationId = "getApiDocumentVersions"
    )
    public ResponseEntity<ApiResponse<List<ApiDocumentVersionResponse>>> getVersions(
            @PathVariable UUID id
    ) {
        List<ApiDocumentVersionResponse> data = apiDocumentService.getVersions(id);

        return ResponseEntity.ok(success("ApiDocument versions fetched successfully.", data));
    }

    @PatchMapping("/{id}/publish")
    @Operation(
            summary = "Publish API document",
            description = "Mark an API document as published. ADMIN only.",
            operationId = "publishApiDocument"
    )
    public ResponseEntity<ApiResponse<ApiDocumentDetailResponse>> publish(
            @PathVariable UUID id
    ) {
        ApiDocumentDetailResponse data = apiDocumentService.publish(id);

        return ResponseEntity.ok(success("ApiDocument published successfully.", data));
    }

    @PatchMapping("/{id}/unpublish")
    @Operation(
            summary = "Unpublish API document",
            description = "Mark an API document as unpublished. ADMIN only.",
            operationId = "unpublishApiDocument"
    )
    public ResponseEntity<ApiResponse<ApiDocumentDetailResponse>> unpublish(
            @PathVariable UUID id
    ) {
        ApiDocumentDetailResponse data = apiDocumentService.unpublish(id);

        return ResponseEntity.ok(success("ApiDocument unpublished successfully.", data));
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
