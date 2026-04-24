package com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.req;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TriggerAiJobRequest {
    
    @NotBlank(message = "Prompt text must not be blank")
    private String promptText;
    
    @NotBlank(message = "Skill code must not be blank")
    private String skillCode;
}
