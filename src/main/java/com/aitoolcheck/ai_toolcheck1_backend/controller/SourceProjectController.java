package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.CreateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.UpdateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceProjectDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceProjectResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceProjectService;
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
public class SourceProjectController {

    private final SourceProjectService sourceProjectService;

    @PostMapping
    public ResponseEntity<SourceProjectDetailResponse> create(@Valid @RequestBody CreateSourceProjectRequest request) {
        SourceProjectDetailResponse response = sourceProjectService.create(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SourceProjectDetailResponse> getById(@PathVariable UUID id) {
        SourceProjectDetailResponse response = sourceProjectService.getById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<SourceProjectResponse>> getAll() {
        List<SourceProjectResponse> response = sourceProjectService.getAll();
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<SourceProjectDetailResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSourceProjectRequest request
    ) {
        SourceProjectDetailResponse response = sourceProjectService.update(id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        sourceProjectService.delete(id);
        return ResponseEntity.noContent().build();
    }
}