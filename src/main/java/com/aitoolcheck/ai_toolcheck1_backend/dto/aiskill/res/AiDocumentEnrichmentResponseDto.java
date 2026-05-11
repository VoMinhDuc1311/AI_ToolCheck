package com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * DTO hứng dữ liệu JSON trả về từ Google Gemini cho luồng Asynchronous Background Task.
 * <p>
 * Lưu ý chống LLM Hallucination:
 * Annotation {@code @JsonIgnoreProperties(ignoreUnknown = true)} được sử dụng để bảo vệ
 * Jackson ObjectMapper. Khi LLM bị ảo giác (hallucination) và tự động sinh ra các trường
 * dữ liệu (JSON keys) rác nằm ngoài dự kiến (không có trong schema/prompt), Jackson
 * sẽ tự động phớt lờ chúng thay vì ném ra lỗi {@code UnrecognizedPropertyException}
 * gây crash luồng RabbitMQ Consumer.
 * </p>
 * <p>
 * Lưu ý về Validation:
 * Các field summary, description, example_request_json, example_response_json đều là
 * OPTIONAL (không có @NotBlank). AI output không bao giờ được đảm bảo 100%. Nếu
 * Gemini trả về null/rỗng cho một field nào đó, hệ thống sẽ vẫn lưu được vào Database
 * với giá trị null thay vì bị FAILED loop vô tận do validation cứng.
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiDocumentEnrichmentResponseDto implements Serializable {

    private static final long serialVersionUID = 1L;

    // Optional — AI có thể không sinh được summary trong mọi trường hợp
    @JsonProperty("summary")
    private String summary;

    // Optional — AI có thể không sinh được description trong mọi trường hợp
    @JsonProperty("description")
    private String description;

    // Optional — Không có request body là bình thường (GET, DELETE, v.v.)
    // Đổi sang JsonNode để chứa object JSON trực tiếp nếu AI quên escape chuỗi
    @JsonProperty("example_request_json")
    private JsonNode exampleRequestJson;

    // Optional — AI có thể không sinh được response example
    // Đổi sang JsonNode để chứa object JSON trực tiếp nếu AI quên escape chuỗi
    @JsonProperty("example_response_json")
    private JsonNode exampleResponseJson;

    @JsonProperty("openapi_fragment_json")
    private JsonNode openapiFragmentJson;
}
