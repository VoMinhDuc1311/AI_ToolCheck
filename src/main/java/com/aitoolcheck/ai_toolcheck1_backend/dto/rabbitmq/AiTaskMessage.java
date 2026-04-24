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
}
