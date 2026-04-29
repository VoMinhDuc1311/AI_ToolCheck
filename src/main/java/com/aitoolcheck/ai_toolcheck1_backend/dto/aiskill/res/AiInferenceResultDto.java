package com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * DTO mapping kết quả phân tích từ Gemini AI.
 * <p>
 * Annotated với Jakarta Validation để đảm bảo cấu trúc dữ liệu tối thiểu
 * ngay sau bước deserialization (Layer 5 – Validation).
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} cho phép LLM trả về
 * field dư thừa mà không làm crash quá trình parse.
 * </p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiInferenceResultDto {

    /** Danh sách endpoints bóc tách được — bắt buộc phải có ít nhất 1 phần tử. */
    @NotNull(message = "endpoints must not be null")
    @NotEmpty(message = "endpoints must not be empty")
    @Valid
    private List<EndpointDto> endpoints = Collections.emptyList();

    // ─────────────────────────────────────────────────────────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EndpointDto {

        @NotNull(message = "endpoint.path must not be null")
        private String path;

        @NotNull(message = "endpoint.httpMethod must not be null")
        private String httpMethod;

        private String description;

        /** Endpoint có yêu cầu xác thực không — fallback false nếu AI không cung cấp. */
        private Boolean authRequired;

        @Valid
        private SourceDto source;

        /** Null-safe: nếu AI bỏ qua trường này, trả về list rỗng thay vì null. */
        @Valid
        private List<ParameterDto> parameters = Collections.emptyList();

        /** Null-safe: tương tự parameters. */
        @Valid
        private List<ResponseDto> responses = Collections.emptyList();

        private Double confidence;
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SourceDto {
        private String className;
        private String methodName;
    }

    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResponseDto {
        private Integer statusCode;
        private String contentType;
    }
}