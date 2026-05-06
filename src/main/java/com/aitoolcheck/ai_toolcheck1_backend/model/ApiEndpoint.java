package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "api_endpoint")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "controller_name")
    private String controllerName;

    @Column(name = "method_name")
    private String methodName;

    @Column(name = "http_method")
    @Enumerated(EnumType.STRING)
    private HttpMethod httpMethod;

    @Column(name = "endpoint_path")
    private String endpointPath;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "operation_id")
    private String operationId;

    @Column(name = "tag_name")
    private String tagName;

    @Column(name = "auth_required")
    private Boolean authRequired;

    @Column(name = "deprecated_flag")
    private Boolean deprecatedFlag;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "summary")
    private String summary;

    @Column(name = "example_request_json", columnDefinition = "TEXT")
    private String exampleRequestJson;

    @Column(name = "example_response_json", columnDefinition = "TEXT")
    private String exampleResponseJson;

    @Column(name = "ai_enriched_flag")
    private Boolean aiEnrichedFlag;
    
    @PrePersist
    public void prePersist() {
        if (this.aiEnrichedFlag == null) {
            this.aiEnrichedFlag = false;
        }
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id", nullable = false)
    private SourceProject sourceProject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_file_id", referencedColumnName = "id")
    private SourceFile sourceFile;

    @OneToMany(mappedBy = "apiEndpoint", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<ApiParameter> apiParameters;




    @OneToMany(mappedBy = "apiEndpoint", fetch = FetchType.LAZY)
    private List<TestCase> testCases;

    @OneToMany(mappedBy = "apiEndpoint", fetch = FetchType.LAZY)
    private List<LegacyInferenceLog> legacyInferenceLogs;

    @OneToMany(mappedBy = "apiEndpoint", fetch = FetchType.LAZY)
    private List<AiJobLog> aiJobLogs;

    // Join-table side: api_endpoint is referenced by endpoint_schema_map
    @OneToMany(mappedBy = "apiEndpoint", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<EndpointSchemaMap> endpointSchemaMaps;
}
