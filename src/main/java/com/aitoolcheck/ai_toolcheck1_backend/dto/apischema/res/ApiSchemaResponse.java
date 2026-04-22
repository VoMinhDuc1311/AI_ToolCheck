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
@Builder
@NoArgsConstructor
@AllArgsConstructor

public class ApiSchemaResponse {
    private UUID id;
    private UUID projectId;
    private String schemaName;
    private SchemaType schemaType;
    private Integer version;
}
