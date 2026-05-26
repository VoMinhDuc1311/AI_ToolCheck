package com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * DTO mapping kết quả phân tích từ AI (Gemini / Ollama).
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} cho phép LLM trả về field dư thừa
 * mà không làm crash quá trình parse.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiInferenceResultDto {

    @NotNull(message = "endpoints must not be null")
    // NOTE: empty list is intentionally valid — helper/non-entrypoint files legitimately return
    // {"endpoints":[]} and must NOT fail DTO validation. The consumer handles this gracefully.
    @Valid
    private List<EndpointDto> endpoints = Collections.emptyList();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EndpointDto {

        @NotNull(message = "endpoint.path must not be null")
        private String path;

        @NotNull(message = "endpoint.httpMethod must not be null")
        private String httpMethod;

        private String description;
        private Boolean authRequired;

        @Valid
        private SourceDto source;

        @Valid
        private List<ParameterDto> parameters = Collections.emptyList();

        @Valid
        private List<ResponseDto> responses = Collections.emptyList();

        @Valid
        private SchemaDto requestSchema;

        @Valid
        private SchemaDto responseSchema;

        private Double confidence;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SourceDto {
        private String className;
        private String methodName;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ParameterDto {

        @NotNull(message = "parameter.name must not be null")
        private String name;

        private String in;
        private String type;
        private Boolean required;
        private String example;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResponseDto {
        private Integer statusCode;
        private String contentType;
    }

    /**
     * Schema inferred by AI cho requestBody hoặc responseBody.
     * {@code schemaType} là chuỗi AI tự mô tả (OBJECT, ARRAY, ...) — không được dùng trong persistence.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SchemaDto {
        private String schemaName;
        private String schemaType;

        @Valid
        private List<FieldDto> fields = Collections.emptyList();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FieldDto {
        private String fieldName;
        private String dataType;
        private Boolean required;
        private Boolean nullable;
        private String description;
        private String exampleValue;
    }
}