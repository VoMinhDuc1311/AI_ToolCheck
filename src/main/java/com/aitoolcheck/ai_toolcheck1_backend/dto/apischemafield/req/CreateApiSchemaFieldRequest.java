package com.aitoolcheck.ai_toolcheck1_backend.dto.apischemafield.req;

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

public class CreateApiSchemaFieldRequest {
    private UUID apiSchemaId;
    private String fieldName;
    private String dataType;
    private Boolean requiredFlag;
    private Boolean nullableFlag;
}
