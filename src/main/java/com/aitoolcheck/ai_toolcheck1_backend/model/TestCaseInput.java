package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "test_case_input")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestCaseInput {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "http_method", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private HttpMethod httpMethod;

    @Column(name = "request_path", nullable = false, length = 500)
    private String requestPath;

    @Column(name = "query_params_json", columnDefinition = "TEXT")
    private String queryParamsJson;

    @Column(name = "headers_json", columnDefinition = "TEXT")
    private String headersJson;

    @Column(name = "request_body_json", columnDefinition = "TEXT")
    private String requestBodyJson;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "timeout_ms", nullable = false)
    private Integer timeoutMs;

    @Column(name = "input_data", columnDefinition = "TEXT")
    private String inputData;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_case_id", referencedColumnName = "id", unique = true, nullable = false)
    private TestCase testCase;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();

        if (timeoutMs == null) {
            timeoutMs = 30000;
        }

        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}