package com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BackendType;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadSourceProjectRequest {

    private String projectKey;
    private String projectName;
    private String description;
    private BackendType backendType;
}