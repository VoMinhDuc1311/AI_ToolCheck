package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.GeminiProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.req.GeminiRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res.GeminiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.GeminiApiClientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
public class GeminiApiClientServiceImpl implements GeminiApiClientService {

    private final WebClient webClient;
    private final GeminiProperties geminiProperties;

    // Inject WebClient đã được cấu hình bean 'geminiWebClient'
    public GeminiApiClientServiceImpl(
            @Qualifier("geminiWebClient") WebClient webClient,
            GeminiProperties geminiProperties) {
        this.webClient = webClient;
        this.geminiProperties = geminiProperties;
    }

    @Override
    public Mono<String> sendPrompt(String promptText) {
        // Build DTO Request dựa vào tham số promptText
        GeminiRequest request = GeminiRequest.builder()
                .contents(List.of(
                        GeminiRequest.Content.builder()
                                .parts(List.of(
                                        GeminiRequest.Part.builder()
                                                .text(promptText)
                                                .build()
                                ))
                                .build()
                ))
                .build();

        // URI Path động từ model trong properties
        String uriPath = "/" + geminiProperties.getModel() + ":generateContent";

        // Gửi POST request thông qua WebClient
        return webClient.post()
                .uri(uriPath)
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS), clientResponse -> {
                    log.warn("Rate limit bị chạm (429 - TOO MANY REQUESTS) từ Gemini API");
                    return clientResponse.bodyToMono(String.class)
                            .flatMap(errorBody -> Mono.error(
                                    new RuntimeException("Rate Limit Exceeded (429): " + errorBody)
                            ));
                })
                .onStatus(HttpStatusCode::is5xxServerError, clientResponse -> {
                    log.error("Server error từ Gemini API: {}", clientResponse.statusCode());
                    return clientResponse.bodyToMono(String.class)
                            .flatMap(errorBody -> Mono.error(
                                    new RuntimeException("Gemini Server Error (" + clientResponse.statusCode() + "): " + errorBody)
                            ));
                })
                .onStatus(HttpStatusCode::is4xxClientError, clientResponse -> {
                    log.error("Client error từ Gemini API: {}", clientResponse.statusCode());
                    return clientResponse.bodyToMono(String.class)
                            .flatMap(errorBody -> Mono.error(
                                    new RuntimeException("Gemini Client Error (" + clientResponse.statusCode() + "): " + errorBody)
                            ));
                })
                .bodyToMono(GeminiResponse.class)
                .map(response -> {
                    String extractedText = response.extractText();
                    if (extractedText == null) {
                        log.warn("Không trích xuất được text từ phản hồi của Gemini: {}", response);
                        throw new RuntimeException("Failed to extract text from Gemini API response");
                    }
                    return extractedText;
                })
                // Thêm cơ chế Retry (Backoff 3 lần, delay 2 giây)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                        .doBeforeRetry(retrySignal -> log.warn("Đang thử lại lần thứ {}/3 do lỗi: {}", 
                                retrySignal.totalRetries() + 1, retrySignal.failure().getMessage())))
                .doOnError(e -> log.error("Ngoại lệ xảy ra trong quá trình gọi Gemini API: ", e));
    }
}
