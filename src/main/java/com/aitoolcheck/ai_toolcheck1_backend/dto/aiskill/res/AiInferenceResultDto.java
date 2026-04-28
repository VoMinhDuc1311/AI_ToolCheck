package com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiInferenceResultDto {
    private List<EndpointDto> endpoints;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EndpointDto {
        private SourceDto source;
        private String path;
        private String httpMethod;
        private String description;
        private List<ParameterDto> parameters;
        private List<ResponseDto> responses;
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
}