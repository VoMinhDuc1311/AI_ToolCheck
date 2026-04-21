package com.aitoolcheck.ai_toolcheck1_backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "api_schema_field")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiSchemaField {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "field_name")
    private String fieldName;

    @Column(name = "data_type")
    private String dataType;

    @Column(name = "required_flag")
    private Boolean requiredFlag;

    @Column(name = "nullable_flag")
    private Boolean nullableFlag;

    // ── Relationships ──────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_schema_id", referencedColumnName = "id", nullable = false)
    private ApiSchema apiSchema;
}
