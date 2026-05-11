package com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * Defensive DTO — ánh xạ một test case từ JSON output của AI.
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} bảo vệ khỏi trường hợp AI
 * sinh dư field.
 * Getter {@code getAssertions()} null-safe để tránh NPE khi AI không sinh
 * assertions.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiTestCaseDto {

    @JsonProperty("case_name")
    private String caseName;

    @JsonProperty("case_type")
    private String caseType;

    @JsonProperty("priority_level")
    private String priorityLevel;

    /**
     * path_params chứa các giá trị thực tế cho biến đường dẫn (ví dụ: {id}).
     */
    @JsonProperty("path_params")
    private JsonNode pathParams;

    /**
     * query_params chứa các tham số truy vấn (ví dụ: ?status=active).
     */
    @JsonProperty("query_params")
    private JsonNode queryParams;

    /**
     * request_body chứa payload thực tế cho POST/PUT/PATCH.
     */
    @JsonProperty("request_body")
    private JsonNode requestBody;

    /**
     * inputData hứng được cả JSON object, primitive, hoặc null từ AI (Legacy fallback).
     */
    @JsonProperty("input_data")
    private JsonNode inputData;

    private List<AiAssertionDto> assertions;

    /**
     * Null-safe getter: trả về empty list thay vì null nếu AI không sinh
     * assertions.
     */
    public List<AiAssertionDto> getAssertions() {
        return assertions == null ? Collections.emptyList() : assertions;
    }
}