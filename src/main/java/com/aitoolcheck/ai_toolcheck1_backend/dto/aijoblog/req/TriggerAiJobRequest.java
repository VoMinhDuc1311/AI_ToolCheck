package com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TriggerAiJobRequest {

    @NotBlank(message = "Prompt text must not be blank")
    private String promptText;

    @NotBlank(message = "Skill code must not be blank")
    private String skillCode;

    @NotNull(message = "projectId must not be null")
    private UUID projectId;

    /** UUID của SourceFile — nullable nếu trigger không gắn với file cụ thể. */
    private UUID sourceFileId;
}
