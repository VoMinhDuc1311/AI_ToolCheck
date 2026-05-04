package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.req.UpdateApiDocumentVersionRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apidocumentversion.res.ApiDocumentVersionDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiDocumentVersionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api-document-versions")
public class ApiDocumentVersionController {

    private final ApiDocumentVersionService apiDocumentVersionService;

    @GetMapping("/{versionId}")
    public ResponseEntity<ApiResponse<ApiDocumentVersionDetailResponse>> getById(
            @PathVariable UUID versionId
    ) {
        ApiDocumentVersionDetailResponse data = apiDocumentVersionService.getById(versionId);

        return ResponseEntity.ok(success("ApiDocumentVersion fetched successfully.", data));
    }

    @PatchMapping("/{versionId}")
    public ResponseEntity<ApiResponse<ApiDocumentVersionDetailResponse>> update(
            @PathVariable UUID versionId,
            @Valid @RequestBody UpdateApiDocumentVersionRequest request
    ) {
        ApiDocumentVersionDetailResponse data = apiDocumentVersionService.update(versionId, request);

        return ResponseEntity.ok(success("ApiDocumentVersion updated successfully.", data));
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