package com.aitoolcheck.ai_toolcheck1_backend.dto.apimetadata.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiMetadataParseResultResponse {

    private UUID projectId;
    private Integer totalControllerFiles;
    private Integer parsedControllerFiles;
    private Integer totalEndpoints;
    private Integer totalParameters;
    private Integer totalSchemas;
    private Integer totalSchemaFields;
    private Integer totalEndpointSchemaMaps;
    private String summary;
}
