package com.aitoolcheck.ai_toolcheck1_backend.dto.rabbitmq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTaskMessage {
    private String jobId;
    private String promptText;
    private String skillCode;

    /** UUID của SourceProject — dùng để ghi Audit Log (LegacyInferenceLog). */
    private String projectId;

    /** UUID của SourceFile — dùng để ghi Audit Log (nullable nếu không liên quan file cụ thể). */
    private String sourceFileId;
}
