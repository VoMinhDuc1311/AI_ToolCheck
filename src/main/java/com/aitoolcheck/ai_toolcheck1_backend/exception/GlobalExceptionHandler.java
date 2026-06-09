package com.aitoolcheck.ai_toolcheck1_backend.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.time.LocalDateTime;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
        @ExceptionHandler(ResourceNotFoundException.class)
        public ResponseEntity<ApiErrorResponse> handleResourceNotFound(
                        ResourceNotFoundException ex, HttpServletRequest request) {

                return build(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), null, request);
        }

        @ExceptionHandler(BadRequestException.class)
        public ResponseEntity<ApiErrorResponse> handleBadRequest(
                        BadRequestException ex, HttpServletRequest request) {

                return build(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), null, request);
        }

        @ExceptionHandler(ConflictException.class)
        public ResponseEntity<ApiErrorResponse> handleConflict(
                        ConflictException ex, HttpServletRequest request) {

                return build(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), null, request);
        }

        @ExceptionHandler(UnauthorizedException.class)
        public ResponseEntity<ApiErrorResponse> handleUnauthorized(
                        UnauthorizedException ex, HttpServletRequest request) {

                return build(HttpStatus.UNAUTHORIZED, "Unauthorized", ex.getMessage(), null, request);
        }

        @ExceptionHandler(ForbiddenException.class)
        public ResponseEntity<ApiErrorResponse> handleForbidden(
                        ForbiddenException ex, HttpServletRequest request) {

                return build(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), null, request);
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(
                        MethodArgumentNotValidException ex, HttpServletRequest request) {

                List<String> details = ex.getBindingResult().getFieldErrors().stream()
                                .map((FieldError fe) -> fe.getField() + ": " + fe.getDefaultMessage())
                                .toList();

                return build(HttpStatus.BAD_REQUEST, "Bad Request", "Validation failed", details, request);
        }

        @ExceptionHandler(ConstraintViolationException.class)
        public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
                        ConstraintViolationException ex, HttpServletRequest request) {

                List<String> details = ex.getConstraintViolations().stream()
                                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                                .toList();

                return build(HttpStatus.BAD_REQUEST, "Bad Request", "Constraint violation", details, request);
        }

        @ExceptionHandler(HttpMessageNotReadableException.class)
        public ResponseEntity<ApiErrorResponse> handleHttpMessageNotReadable(
                        HttpMessageNotReadableException ex, HttpServletRequest request) {

                String detail = null;
                Throwable cause = ex.getMostSpecificCause();
                if (cause != null && cause != ex) {
                        String causeMsg = cause.getMessage();
                        // Keep detail concise – truncate at newline if present
                        if (causeMsg != null) {
                                int newline = causeMsg.indexOf('\n');
                                detail = (newline > 0) ? causeMsg.substring(0, newline).strip() : causeMsg.strip();
                        }
                }

                List<String> details = (detail != null) ? List.of(detail) : null;
                return build(HttpStatus.BAD_REQUEST, "Bad Request",
                                "Malformed JSON request or invalid field value", details, request);
        }

        @ExceptionHandler(MethodArgumentTypeMismatchException.class)
        public ResponseEntity<ApiErrorResponse> handleMethodArgumentTypeMismatch(
                        MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

                String expectedType = (ex.getRequiredType() != null)
                                ? ex.getRequiredType().getSimpleName()
                                : "unknown";

                String detail = String.format("parameter '%s' with value '%s' could not be converted to %s",
                                ex.getName(), ex.getValue(), expectedType);

                return build(HttpStatus.BAD_REQUEST, "Bad Request",
                                "Invalid request parameter type", List.of(detail), request);
        }

        @ExceptionHandler(MissingServletRequestParameterException.class)
        public ResponseEntity<ApiErrorResponse> handleMissingServletRequestParameter(
                        MissingServletRequestParameterException ex, HttpServletRequest request) {

                String detail = String.format("required parameter '%s' of type '%s' is missing",
                                ex.getParameterName(), ex.getParameterType());

                return build(HttpStatus.BAD_REQUEST, "Bad Request",
                                "Missing required request parameter", List.of(detail), request);
        }

        @ExceptionHandler(MissingRequestHeaderException.class)
        public ResponseEntity<ApiErrorResponse> handleMissingRequestHeader(
                        MissingRequestHeaderException ex, HttpServletRequest request) {

                String detail = String.format("required header '%s' is missing", ex.getHeaderName());

                return build(HttpStatus.BAD_REQUEST, "Bad Request",
                                "Missing required request header", List.of(detail), request);
        }

        @ExceptionHandler(ServletRequestBindingException.class)
        public ResponseEntity<ApiErrorResponse> handleServletRequestBinding(
                        ServletRequestBindingException ex, HttpServletRequest request) {

                return build(HttpStatus.BAD_REQUEST, "Bad Request",
                                ex.getMessage(), null, request);
        }

        @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
        public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
                        HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {

                return build(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed",
                                ex.getMessage(), null, request);
        }

        @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
        public ResponseEntity<ApiErrorResponse> handleMediaTypeNotSupported(
                        HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {

                return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type",
                                ex.getMessage(), null, request);
        }

        @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
        public ResponseEntity<ApiErrorResponse> handleMediaTypeNotAcceptable(
                        HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {

                return build(HttpStatus.NOT_ACCEPTABLE, "Not Acceptable",
                                ex.getMessage(), null, request);
        }

        @ExceptionHandler(DataIntegrityViolationException.class)
        public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(
                        DataIntegrityViolationException ex, HttpServletRequest request) {

                return build(HttpStatus.CONFLICT, "Conflict",
                                dataIntegrityMessage(ex, request),
                                null, request);
        }

        @ExceptionHandler(NoHandlerFoundException.class)
        public ResponseEntity<ApiErrorResponse> handleNoHandlerFound(
                        NoHandlerFoundException ex, HttpServletRequest request) {

                return build(HttpStatus.NOT_FOUND, "Not Found",
                                ex.getMessage(), null, request);
        }

        @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
        public ResponseEntity<ApiErrorResponse> handleNoResourceFound(
                        org.springframework.web.servlet.resource.NoResourceFoundException ex,
                        HttpServletRequest request) {

                return build(HttpStatus.NOT_FOUND, "Not Found",
                                "Endpoint or resource not found: " + ex.getResourcePath(), null, request);
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<ApiErrorResponse> handleGenericException(
                        Exception ex, HttpServletRequest request) {

                return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                                ex.getMessage(), null, request);
        }

        private ResponseEntity<ApiErrorResponse> build(
                        HttpStatus status, String error, String message,
                        List<String> details, HttpServletRequest request) {

                ApiErrorResponse body = ApiErrorResponse.builder()
                                .timestamp(LocalDateTime.now())
                                .status(status.value())
                                .error(error)
                                .message(message)
                                .path(request.getRequestURI())
                                .details(details)
                                .build();

                return ResponseEntity.status(status).body(body);
        }

        private String dataIntegrityMessage(DataIntegrityViolationException ex, HttpServletRequest request) {
                String path = request.getRequestURI() == null ? "" : request.getRequestURI();
                String message = flattenExceptionMessage(ex);

                if (isCreateSourceProjectRequest(path)) {
                        if (message.contains("project_key") || message.contains("projectkey")) {
                                return "Project key already exists.";
                        }
                        if (message.contains("project_name") || message.contains("projectname")) {
                                return "Project name already exists.";
                        }
                        if (message.contains("source_project")) {
                                return "Source project could not be created because a unique constraint was violated.";
                        }
                }

                return "Operation failed due to a data integrity constraint. Please ensure all dependent data is resolved before retrying.";
        }

        private boolean isCreateSourceProjectRequest(String path) {
                return path.endsWith("/v1/source-projects") || path.endsWith("/api/v1/source-projects");
        }

        private String flattenExceptionMessage(Throwable throwable) {
                StringBuilder sb = new StringBuilder();
                Throwable current = throwable;
                while (current != null) {
                        if (current.getMessage() != null) {
                                sb.append(' ').append(current.getMessage().toLowerCase());
                        }
                        current = current.getCause();
                }
                return sb.toString();
        }
}
