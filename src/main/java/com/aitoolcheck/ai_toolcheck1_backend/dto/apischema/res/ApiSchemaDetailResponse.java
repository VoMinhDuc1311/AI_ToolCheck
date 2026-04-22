package com.aitoolcheck.ai_toolcheck1_backend.dto.apischema.res;

import com.aitoolcheck.ai_toolcheck1_backend.enums.SchemaType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor

public class ApiSchemaDetailResponse {
    private UUID id;
    private UUID projectId;
    private String schemaName;
    private SchemaType schemaType;
    private String description;
    private Integer version;
}
