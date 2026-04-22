package com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BackendType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateSourceProjectRequest {

    private String projectKey;
    private String projectName;
    private String description;
    private BackendType backendType;
}