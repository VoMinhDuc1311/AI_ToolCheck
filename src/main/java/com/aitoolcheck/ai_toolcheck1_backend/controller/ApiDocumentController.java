package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.req.CreateApiDocumentRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.req.UpdateApiDocumentRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.res.ApiDocumentDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.res.ApiDocumentVersionResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiDocumentService;
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
@RequestMapping("/api-documents")
public class ApiDocumentController {

    private final ApiDocumentService apiDocumentService;

    @PostMapping
    public ResponseEntity<ApiResponse<ApiDocumentDetailResponse>> create(
            @Valid @RequestBody CreateApiDocumentRequest request
    ) {
        ApiDocumentDetailResponse data = apiDocumentService.create(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(success("ApiDocument created successfully.", data));
    }

    @GetMapping("/project/{projectId}")
    public ResponseEntity<ApiResponse<ApiDocumentDetailResponse>> getByProjectId(
            @PathVariable UUID projectId
    ) {
        ApiDocumentDetailResponse data = apiDocumentService.getByProjectId(projectId);

        return ResponseEntity.ok(success("ApiDocument fetched successfully.", data));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ApiDocumentDetailResponse>> getById(
            @PathVariable UUID id
    ) {
        ApiDocumentDetailResponse data = apiDocumentService.getById(id);

        return ResponseEntity.ok(success("ApiDocument fetched successfully.", data));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<ApiDocumentDetailResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateApiDocumentRequest request
    ) {
        ApiDocumentDetailResponse data = apiDocumentService.update(id, request);

        return ResponseEntity.ok(success("ApiDocument updated successfully.", data));
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<ApiResponse<List<ApiDocumentVersionResponse>>> getVersions(
            @PathVariable UUID id
    ) {
        List<ApiDocumentVersionResponse> data = apiDocumentService.getVersions(id);

        return ResponseEntity.ok(success("ApiDocument versions fetched successfully.", data));
    }

    @PatchMapping("/{id}/publish")
    public ResponseEntity<ApiResponse<ApiDocumentDetailResponse>> publish(
            @PathVariable UUID id
    ) {
        ApiDocumentDetailResponse data = apiDocumentService.publish(id);

        return ResponseEntity.ok(success("ApiDocument published successfully.", data));
    }

    @PatchMapping("/{id}/unpublish")
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