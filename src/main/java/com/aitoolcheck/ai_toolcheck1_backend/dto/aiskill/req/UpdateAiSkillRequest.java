package com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor

public class UpdateAiSkillRequest {
    private String skillCode;
    private String skillName;
    private String description;
}
