package com.aitoolcheck.ai_toolcheck1_backend.feature.api_document.entity;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ParamIn;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "api_parameter")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiParameter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "param_name")
    private String paramName;

    @Column(name = "param_in")
    @Enumerated(EnumType.STRING)
    private ParamIn paramIn;

    @Column(name = "data_type")
    private String dataType;

    @Column(name = "required_flag")
    private Boolean requiredFlag;

    @Column(name = "example_value")
    private String exampleValue;

    // ── Relationships ──────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_endpoint_id", referencedColumnName = "id", nullable = false)
    private ApiEndpoint apiEndpoint;
}
