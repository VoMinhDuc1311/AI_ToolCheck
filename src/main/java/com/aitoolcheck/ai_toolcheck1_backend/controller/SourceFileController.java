package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileUploadResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class SourceFileController {

    private final SourceFileService sourceFileService;

    @PostMapping(value = "/source-projects/{projectId}/upload-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SourceFileUploadResponse> uploadZip(
            @PathVariable UUID projectId,
            @RequestPart("file") MultipartFile file
    ) {
        SourceFileUploadResponse response = sourceFileService.uploadZip(projectId, file);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/source-files/project/{projectId}")
    public ResponseEntity<List<SourceFileResponse>> getByProjectId(@PathVariable UUID projectId) {
        return ResponseEntity.ok(sourceFileService.getByProjectId(projectId));
    }

    @GetMapping("/source-files/{id}")
    public ResponseEntity<SourceFileDetailResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(sourceFileService.getById(id));
    }
}
