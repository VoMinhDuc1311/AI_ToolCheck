package com.aitoolcheck.ai_toolcheck1_backend.service.runner;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.res.PreparedHttpRequestResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestHttpExecutorTest {

    @Mock
    private RestTemplate restTemplate;

    @Test
    void executeTestRun_usesFinalUrlWithQueryParams() {
        TestHttpExecutor executor = new TestHttpExecutor(restTemplate, new ObjectMapper());
        PreparedHttpRequestResponse prepared = PreparedHttpRequestResponse.builder()
                .method(HttpMethod.GET)
                .finalUrl("https://greeting-demo.example.com/greeting?name=ChatGPT")
                .headers(Map.of("Accept", "application/json"))
                .queryParams(Map.of("name", "ChatGPT"))
                .contentType("application/json")
                .timeoutMs(30000)
                .build();

        when(restTemplate.exchange(
                eq("https://greeting-demo.example.com/greeting?name=ChatGPT"),
                eq(org.springframework.http.HttpMethod.GET),
                org.mockito.ArgumentMatchers.<HttpEntity<?>>any(),
                eq(String.class)
        )).thenReturn(ResponseEntity.ok("{\"message\":\"Hello ChatGPT\"}"));

        ExecutedHttpResponse response = executor.execute(prepared);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(
                urlCaptor.capture(),
                eq(org.springframework.http.HttpMethod.GET),
                org.mockito.ArgumentMatchers.<HttpEntity<?>>any(),
                eq(String.class)
        );
        assertThat(urlCaptor.getValue()).isEqualTo("https://greeting-demo.example.com/greeting?name=ChatGPT");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.connectionError()).isFalse();
    }
}
