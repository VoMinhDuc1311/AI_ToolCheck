package com.aitoolcheck.ai_toolcheck1_backend.dto.apischemafield.res;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter

public class ApiSchemaFieldDetailResponse {
    private UUID id;
    private UUID apiSchemaId;
    private String fieldName;
    private String dataType;
    private Boolean requiredFlag;
    private Boolean nullableFlag;
}
