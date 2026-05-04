package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.CreateAiSkillRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.UpdateAiSkillRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiSkillResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.req.PageRequestDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.PageResponseDto;

import java.util.UUID;

public interface AiSkillService {
    AiSkillResponse createAiSkill(CreateAiSkillRequest request);
    AiSkillResponse updateAiSkill(UUID id, UpdateAiSkillRequest request);
    AiSkillResponse getAiSkillById(UUID id);
    AiSkillResponse resolveBySkillCode(String skillCode);
    PageResponseDto<AiSkillResponse> getAllAiSkills(PageRequestDto pageRequest);
}
