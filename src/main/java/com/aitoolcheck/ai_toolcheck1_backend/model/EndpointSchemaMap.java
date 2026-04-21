package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.UsageType;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "endpoint_schema_map")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EndpointSchemaMap {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "usage_type")
    @Enumerated(EnumType.STRING)
    private UsageType usageType;

    // ── Relationships ──────────────────────────────────────────────────────────

    // This entity acts as the explicit join table for the api_endpoint <-> api_schema M-N
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_endpoint_id", referencedColumnName = "id", nullable = false)
    private ApiEndpoint apiEndpoint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_schema_id", referencedColumnName = "id", nullable = false)
    private ApiSchema apiSchema;
}
