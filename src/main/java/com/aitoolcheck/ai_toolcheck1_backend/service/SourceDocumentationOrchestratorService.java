package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceDocumentationPipelineResponse;

import java.util.UUID;

/**
 * Contract for the service that orchestrates the full "source → documentation" pipeline.
 *
 * <p>Implementations handle: source analysis → optional AST parse → optional AI job enqueue
 * → optional OpenAPI generation, based on detected source style.
 */
public interface SourceDocumentationOrchestratorService {

    /**
     * Execute the full documentation pipeline for the given project.
     * Requires the current authenticated user to have at least EDITOR-level access.
     */
    SourceDocumentationPipelineResponse generateDocsFromSource(UUID projectId);
}
