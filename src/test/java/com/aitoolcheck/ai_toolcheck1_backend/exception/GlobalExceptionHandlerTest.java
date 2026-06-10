package com.aitoolcheck.ai_toolcheck1_backend.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private static final String REUPLOAD_METADATA_CLEANUP_MESSAGE =
            "Re-upload failed because existing generated data depends on previous source metadata. "
                    + "Please retry after metadata cleanup is completed.";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void createProject_dataIntegrityProjectKeyConflict_returnsProjectKeyConflict() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/source-projects");

        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "Duplicate entry 'ADMIN-UI-AI-KEY-009' for key 'source_project.project_key'");

        ResponseEntity<ApiErrorResponse> response = handler.handleDataIntegrityViolation(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Project key already exists.");
        assertThat(response.getBody().getMessage()).isNotEqualTo(REUPLOAD_METADATA_CLEANUP_MESSAGE);
    }

    @Test
    void conflictException_returnsConflictStatusAndOriginalMessage() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/source-projects");

        ResponseEntity<ApiErrorResponse> response = handler.handleConflict(
                new ConflictException("Project key already exists: ADMIN-UI-AI-KEY-009"),
                request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage())
                .isEqualTo("Project key already exists: ADMIN-UI-AI-KEY-009")
                .isNotEqualTo(REUPLOAD_METADATA_CLEANUP_MESSAGE);
    }

    @Test
    void createSourceProject_dbIntegrityError_doesNotReturnReuploadMessage() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/source-projects");

        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "Field 'archived_flag' doesn't have a default value on table source_project");

        ResponseEntity<ApiErrorResponse> response = handler.handleDataIntegrityViolation(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage())
                .isEqualTo("Failed to create source project because database constraint was violated.")
                .isNotEqualTo(REUPLOAD_METADATA_CLEANUP_MESSAGE);
    }

    @Test
    void reuploadSource_conflictStillReturnsReuploadMessageOnlyForActualReuploadFlow() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/source-projects/11111111-1111-1111-1111-111111111111/upload-zip");

        ResponseEntity<ApiErrorResponse> response = handler.handleConflict(
                new ConflictException(REUPLOAD_METADATA_CLEANUP_MESSAGE),
                request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo(REUPLOAD_METADATA_CLEANUP_MESSAGE);
    }
}
