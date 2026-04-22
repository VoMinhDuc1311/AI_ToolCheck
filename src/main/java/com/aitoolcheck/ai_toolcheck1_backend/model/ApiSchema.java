package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.SchemaType;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "api_schema")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiSchema {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "schema_name")
    private String schemaName;

    @Column(name = "schema_type")
    @Enumerated(EnumType.STRING)
    private SchemaType schemaType;

    @Column(name = "description")
    private String description;

    @Column(name = "version_no")
    private Integer versionNo;



    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id", nullable = false)
    private SourceProject sourceProject;

    @OneToMany(mappedBy = "apiSchema", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<ApiSchemaField> apiSchemaFields;

    @OneToMany(mappedBy = "apiSchema", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<EndpointSchemaMap> endpointSchemaMaps;
}
