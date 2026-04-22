package com.aitoolcheck.ai_toolcheck1_backend.dto.apischema.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.SchemaType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder

public class UpdateApiSchemaRequest {
    private String schemaName;
    private SchemaType schemaType;
    private String description;
    private Integer version;
}
