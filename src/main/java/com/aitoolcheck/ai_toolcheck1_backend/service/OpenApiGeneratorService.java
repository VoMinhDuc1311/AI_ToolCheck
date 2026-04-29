package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.openapi.res.OpenApiGenerateResponse;

import java.util.Map;
import java.util.UUID;

public interface OpenApiGeneratorService {

    /**
     * Generates the full OpenAPI 3.0 JSON for a project in-memory.
     * Read-only — does not write to the database.
     */
    Map<String, Object> generateOpenApiJson(UUID projectId);

    /**
     * Generates the full OpenAPI 3.0 JSON, persists it as a new ApiDocumentVersion,
     * and returns a summary response.
     */
    OpenApiGenerateResponse generateAndSaveOpenApi(UUID projectId);
}
