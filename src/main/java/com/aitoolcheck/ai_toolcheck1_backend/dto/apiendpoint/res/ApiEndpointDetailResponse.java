package com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.res;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Service
@Builder
@AllArgsConstructor
@NoArgsConstructor

public class ApiEndpointDetailResponse {
    private UUID id;
    private UUID projectId;
    private UUID sourceFileId;
    private String controllerName;
    private String methodName;
    private HttpMethod httpMethod;
    private String endpointPath;
    private String operationId;
    private String tagName;
    private Boolean authRequired;
    private Boolean deprecatedFlag;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
