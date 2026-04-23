package com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiSkillResponse {

    private UUID id;
    private String skillCode;
    private String skillName;
    private String description;
}
