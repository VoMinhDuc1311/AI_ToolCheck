package com.aitoolcheck.ai_toolcheck1_backend.service.runner;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link TestRequestBuilder}.
 *
 * <p>Core regression: headers and queryParams must be deserialized from stored JSON strings
 * into {@code Map<String,Object>} — NOT left as raw {@link com.fasterxml.jackson.databind.JsonNode}.
 * This prevents Jackson from serializing internal JsonNode bean metadata
 * ({@code nodeType}, {@code array}, {@code containerNode}, etc.) in API responses.
 */
class TestRequestBuilderTest {

    private TestRequestBuilder builder;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        builder = new TestRequestBuilder(objectMapper);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core regression: prepareTestRun preserves headers and queryParams
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * prepareTestRun_withManualTestCase_preservesHeadersAndQueryParams
     *
     * Given: testCaseInput.queryParamsJson = {"name":"ChatGPT"}
     *        testCaseInput.headersJson     = {"Accept":"application/json"}
     *        testCaseInput.requestBodyJson = null
     * When:  build() is called
     * Then:  preparedRequest.queryParams.name = "ChatGPT"
     *        preparedRequest.headers.Accept   = "application/json"
     *        preparedRequest.body             = null
     */
    @Test
    void prepareTestRun_withManualTestCase_preservesHeadersAndQueryParams() throws JsonProcessingException {
        String queryParamsJson = objectMapper.writeValueAsString(Map.of("name", "ChatGPT"));
        String headersJson     = objectMapper.writeValueAsString(Map.of("Accept", "application/json"));

        TestCaseInput input = buildInput(HttpMethod.GET, "/greeting", queryParamsJson, headersJson, null);

        PreparedHttpRequestResponse prepared = builder.build("http://localhost:8080/api", input);

        assertNotNull(prepared, "prepared must not be null");
        assertEquals("http://localhost:8080/api/greeting?name=ChatGPT", prepared.getFinalUrl());

        // ── queryParams ──
        assertNotNull(prepared.getQueryParams(), "queryParams must not be null");
        assertThat(prepared.getQueryParams()).containsEntry("name", "ChatGPT");

        // ── headers ──
        assertNotNull(prepared.getHeaders(), "headers must not be null");
        assertThat(prepared.getHeaders()).containsEntry("Accept", "application/json");

        // ── body ──
        assertNull(prepared.getBody(), "body must be null for a GET with no requestBody");
    }

    /**
     * prepareTestRun_responseDoesNotExposeJsonNodeMetadata
     *
     * Assert that serialized response does not contain internal JsonNode fields:
     * nodeType, containerNode, array, object, bigDecimal, valueNode
     */
    @Test
    void prepareTestRun_responseDoesNotExposeJsonNodeMetadata() throws JsonProcessingException {
        String queryParamsJson = objectMapper.writeValueAsString(Map.of("name", "ChatGPT"));
        String headersJson     = objectMapper.writeValueAsString(Map.of("Accept", "application/json"));

        TestCaseInput input = buildInput(HttpMethod.GET, "/greeting", queryParamsJson, headersJson, null);

        PreparedHttpRequestResponse prepared = builder.build("http://localhost:8080/api", input);

        // Serialize the response DTO the same way Spring Boot would
        String json = objectMapper.writeValueAsString(prepared);

        // Must NOT contain any JsonNode metadata fields
        assertThat(json)
                .doesNotContain("\"nodeType\"")
                .doesNotContain("\"containerNode\"")
                .doesNotContain("\"array\"")
                .doesNotContain("\"valueNode\"")
                .doesNotContain("\"bigDecimal\"")
                .doesNotContain("\"object\":");

        // Must contain real values
        assertThat(json)
                .contains("\"name\"")
                .contains("\"ChatGPT\"")
                .contains("\"Accept\"")
                .contains("\"application/json\"");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // URL construction
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void build_constructsFinalUrlCorrectly() throws JsonProcessingException {
        TestCaseInput input = buildInput(HttpMethod.GET, "/greeting", null, null, null);

        PreparedHttpRequestResponse prepared = builder.build("http://localhost:8080/api", input);

        assertEquals("http://localhost:8080/api/greeting", prepared.getFinalUrl());
        assertEquals("http://localhost:8080/api",          prepared.getBaseUrl());
        assertEquals("/greeting",                           prepared.getRequestPath());
    }

    @Test
    void build_withQueryParams_appendsQueryStringToFinalUrl() throws JsonProcessingException {
        String queryParamsJson = objectMapper.writeValueAsString(Map.of("name", "ChatGPT"));
        TestCaseInput input = buildInput(HttpMethod.GET, "/greeting", queryParamsJson, null, null);

        PreparedHttpRequestResponse prepared = builder.build("http://localhost:8080/api", input);

        assertEquals("http://localhost:8080/api/greeting?name=ChatGPT", prepared.getFinalUrl());
        assertThat(prepared.getQueryParams()).containsEntry("name", "ChatGPT");
    }

    @Test
    void prepareTestRun_withManualTestCase_finalUrlContainsQueryParams() throws JsonProcessingException {
        String queryParamsJson = objectMapper.writeValueAsString(Map.of("name", "ChatGPT"));
        String headersJson = objectMapper.writeValueAsString(Map.of("Accept", "application/json"));
        TestCaseInput input = buildInput(HttpMethod.GET, "/greeting", queryParamsJson, headersJson, null);

        PreparedHttpRequestResponse prepared = builder.build("https://greeting-demo.example.com", input);

        assertEquals("https://greeting-demo.example.com/greeting?name=ChatGPT", prepared.getFinalUrl());
        assertThat(prepared.getHeaders()).containsEntry("Accept", "application/json");
        assertThat(prepared.getQueryParams()).containsEntry("name", "ChatGPT");
        assertNull(prepared.getBody());
    }

    @Test
    void build_withSpecialCharsInQueryParams_encodesCorrectly() throws JsonProcessingException {
        String queryParamsJson = objectMapper.writeValueAsString(Map.of("q", "a+b & c/d"));
        TestCaseInput input = buildInput(HttpMethod.GET, "/search", queryParamsJson, null, null);

        PreparedHttpRequestResponse prepared = builder.build("https://api.example.com", input);

        assertThat(prepared.getFinalUrl())
                .startsWith("https://api.example.com/search?q=")
                .contains("a%2Bb")
                .contains("%26")
                .doesNotContain(" ");
    }

    @Test
    void build_withUnicodeQueryParams_encodesCorrectly() throws JsonProcessingException {
        String queryParamsJson = objectMapper.writeValueAsString(Map.of("name", "Jöhn Döe"));
        TestCaseInput input = buildInput(HttpMethod.GET, "/greeting", queryParamsJson, null, null);

        PreparedHttpRequestResponse prepared = builder.build("https://greeting-demo.example.com", input);

        assertThat(prepared.getFinalUrl())
                .startsWith("https://greeting-demo.example.com/greeting?name=")
                .contains("J%C3%B6hn")
                .contains("D%C3%B6e")
                .doesNotContain(" ");
    }

    @Test
    void build_withListQueryParams_repeatsParamOrHandlesSafely() throws JsonProcessingException {
        String queryParamsJson = objectMapper.writeValueAsString(Map.of("tag", List.of("a", "b")));
        TestCaseInput input = buildInput(HttpMethod.GET, "/api/products", queryParamsJson, null, null);

        PreparedHttpRequestResponse prepared = builder.build("https://api.example.com", input);

        assertEquals("https://api.example.com/api/products?tag=a&tag=b", prepared.getFinalUrl());
        assertThat(prepared.getQueryParams()).containsKey("tag");
    }

    @Test
    void build_withNullQueryParamValue_skipsParam() {
        String queryParamsJson = "{\"name\":null,\"tag\":\"a\"}";
        TestCaseInput input = buildInput(HttpMethod.GET, "/api/products", queryParamsJson, null, null);

        PreparedHttpRequestResponse prepared = builder.build("https://api.example.com", input);

        assertEquals("https://api.example.com/api/products?tag=a", prepared.getFinalUrl());
        assertThat(prepared.getQueryParams()).containsEntry("name", null);
    }

    @Test
    void build_stripsTrailingSlashFromBaseUrl() throws JsonProcessingException {
        TestCaseInput input = buildInput(HttpMethod.GET, "/greeting", null, null, null);

        PreparedHttpRequestResponse prepared = builder.build("http://localhost:8080/api/", input);

        assertEquals("http://localhost:8080/api/greeting", prepared.getFinalUrl());
    }

    @Test
    void build_preservesBaseUrlFromRequest_doesNotHardcode() throws JsonProcessingException {
        // This verifies that the builder uses the exact baseUrl provided, not any hardcoded value.
        String publicBaseUrl = "https://aitoolcheck-md.duckdns.org/api";
        TestCaseInput input = buildInput(HttpMethod.GET, "/greeting", null, null, null);

        PreparedHttpRequestResponse prepared = builder.build(publicBaseUrl, input);

        assertEquals(publicBaseUrl,                               prepared.getBaseUrl());
        assertEquals(publicBaseUrl + "/greeting",                 prepared.getFinalUrl());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Null / empty JSON fields
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void build_withNullQueryParamsJson_returnsNullQueryParams() throws JsonProcessingException {
        TestCaseInput input = buildInput(HttpMethod.GET, "/greeting", null, null, null);

        PreparedHttpRequestResponse prepared = builder.build("http://localhost:8080/api", input);

        assertEquals("http://localhost:8080/api/greeting", prepared.getFinalUrl());
        assertThat(prepared.getFinalUrl()).doesNotContain("?");
        assertNull(prepared.getQueryParams(), "queryParams must be null when stored string is null");
        assertNull(prepared.getBody(),        "body must be null for GET with no requestBody");
    }

    @Test
    void build_withEmptyQueryParamsJson_returnsEmptyMap() throws JsonProcessingException {
        TestCaseInput input = buildInput(HttpMethod.GET, "/greeting", "{}", null, null);

        PreparedHttpRequestResponse prepared = builder.build("http://localhost:8080/api", input);

        assertEquals("http://localhost:8080/api/greeting", prepared.getFinalUrl());
        assertThat(prepared.getFinalUrl()).doesNotContain("?");
        assertNotNull(prepared.getQueryParams());
        assertThat(prepared.getQueryParams()).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Request body
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void build_withRequestBodyJson_parsesBodyAsObject() throws JsonProcessingException {
        String bodyJson = objectMapper.writeValueAsString(Map.of("model", "gpt-4", "temperature", 0.7));
        TestCaseInput input = buildInput(HttpMethod.POST, "/api/chat", null, null, bodyJson);

        PreparedHttpRequestResponse prepared = builder.build("http://localhost:8080", input);

        assertNotNull(prepared.getBody(), "body must not be null");
        // Body must be a Map (deserialized from JSON object), not a JsonNode
        assertThat(prepared.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> bodyMap = (Map<String, Object>) prepared.getBody();
        assertThat(bodyMap).containsEntry("model", "gpt-4");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP method preserved
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void build_preservesHttpMethod() throws JsonProcessingException {
        TestCaseInput input = buildInput(HttpMethod.POST, "/api/users", null, null, "{}");

        PreparedHttpRequestResponse prepared = builder.build("http://localhost:8080", input);

        assertEquals(HttpMethod.POST, prepared.getMethod());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Validation failures
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void build_withNullInput_throwsBadRequest() {
        assertThatThrownBy(() -> builder.build("http://localhost:8080", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("testCaseInput is required");
    }

    @Test
    void build_withInvalidBaseUrl_throwsBadRequest() throws JsonProcessingException {
        TestCaseInput input = buildInput(HttpMethod.GET, "/greeting", null, null, null);

        assertThatThrownBy(() -> builder.build("ftp://not-allowed.com", input))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("baseUrl must use http or https");
    }

    @Test
    void build_withInvalidQueryParamsJson_throwsBadRequest() {
        TestCaseInput input = buildInput(HttpMethod.GET, "/greeting", "not-valid-json{{{", null, null);

        assertThatThrownBy(() -> builder.build("http://localhost:8080", input))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("queryParamsJson");
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void build_withRawSpecialCharsInPath_encodesCorrectly() throws JsonProcessingException {
        TestCaseInput input = buildInput(HttpMethod.GET, "/legacy/customers/CUST_!@#$%^", null, null, null);

        PreparedHttpRequestResponse prepared = builder.build("http://localhost:8080", input);

        assertEquals("http://localhost:8080/legacy/customers/CUST_!@%23$%25%5E", prepared.getFinalUrl());
        assertEquals("/legacy/customers/CUST_!@%23$%25%5E", prepared.getRequestPath());
    }

    @Test
    void build_withAlreadyEncodedPath_doesNotDoubleEncode() throws JsonProcessingException {
        TestCaseInput input = buildInput(HttpMethod.GET, "/legacy/customers/CUST_!@%23$%25%5E", null, null, null);

        PreparedHttpRequestResponse prepared = builder.build("http://localhost:8080", input);

        assertEquals("http://localhost:8080/legacy/customers/CUST_!@%23$%25%5E", prepared.getFinalUrl());
        assertEquals("/legacy/customers/CUST_!@%23$%25%5E", prepared.getRequestPath());
    }

    @Test
    void build_withSpaceInPath_encodesToPercent20() throws JsonProcessingException {
        TestCaseInput input = buildInput(HttpMethod.GET, "/legacy/customers/CUST ID", null, null, null);

        PreparedHttpRequestResponse prepared = builder.build("http://localhost:8080", input);

        assertEquals("http://localhost:8080/legacy/customers/CUST%20ID", prepared.getFinalUrl());
        assertEquals("/legacy/customers/CUST%20ID", prepared.getRequestPath());
    }

    @Test
    void build_withPlusInPath_encodesToPercent2B() throws JsonProcessingException {
        TestCaseInput input = buildInput(HttpMethod.GET, "/legacy/customers/CUST+ID", null, null, null);

        PreparedHttpRequestResponse prepared = builder.build("http://localhost:8080", input);

        assertEquals("http://localhost:8080/legacy/customers/CUST%2BID", prepared.getFinalUrl());
        assertEquals("/legacy/customers/CUST%2BID", prepared.getRequestPath());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private TestCaseInput buildInput(
            HttpMethod httpMethod,
            String requestPath,
            String queryParamsJson,
            String headersJson,
            String requestBodyJson) {

        return TestCaseInput.builder()
                .httpMethod(httpMethod)
                .requestPath(requestPath)
                .queryParamsJson(queryParamsJson)
                .headersJson(headersJson)
                .requestBodyJson(requestBodyJson)
                .contentType("application/json")
                .timeoutMs(30000)
                .build();
    }
}
