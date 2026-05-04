package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.CreateAiSkillRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.UpdateAiSkillRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiSkillResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.req.PageRequestDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.PageResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiSkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/v1/ai-skills")
@RequiredArgsConstructor
public class AiSkillController {

    private final AiSkillService aiSkillService;

    @PostMapping
    public ApiResponse<AiSkillResponse> createAiSkill(@RequestBody CreateAiSkillRequest request) {
        AiSkillResponse response = aiSkillService.createAiSkill(request);
        return ApiResponse.<AiSkillResponse>builder()
                .code("200")
                .message("Success")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build();
    }

    @PutMapping("/{id}")
    public ApiResponse<AiSkillResponse> updateAiSkill(@PathVariable UUID id, @RequestBody UpdateAiSkillRequest request) {
        AiSkillResponse response = aiSkillService.updateAiSkill(id, request);
        return ApiResponse.<AiSkillResponse>builder()
                .code("200")
                .message("Success")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build();
    }

    @GetMapping("/{id}")
    public ApiResponse<AiSkillResponse> getAiSkillById(@PathVariable UUID id) {
        AiSkillResponse response = aiSkillService.getAiSkillById(id);
        return ApiResponse.<AiSkillResponse>builder()
                .code("200")
                .message("Success")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build();
    }

    @GetMapping("/code/{skillCode}")
    public ApiResponse<AiSkillResponse> resolveBySkillCode(@PathVariable String skillCode) {
        AiSkillResponse response = aiSkillService.resolveBySkillCode(skillCode);
        return ApiResponse.<AiSkillResponse>builder()
                .code("200")
                .message("Success")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build();
    }

    @GetMapping
    public ApiResponse<PageResponseDto<AiSkillResponse>> getAllAiSkills(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        
        PageRequestDto pageRequest = PageRequestDto.builder()
                .page(page)
                .pageSize(pageSize)
                .sortBy(sortBy)
                .sortDir(sortDir)
                .build();
        
        PageResponseDto<AiSkillResponse> response = aiSkillService.getAllAiSkills(pageRequest);
        return ApiResponse.<PageResponseDto<AiSkillResponse>>builder()
                .code("200")
                .message("Success")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
