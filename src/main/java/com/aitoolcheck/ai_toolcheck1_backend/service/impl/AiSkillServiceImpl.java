package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.CreateAiSkillRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.req.UpdateAiSkillRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiSkillResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.req.PageRequestDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.PageResponseDto;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiSkill;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiSkillRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiSkillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Sort;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiSkillServiceImpl implements AiSkillService {

    private final AiSkillRepository aiSkillRepository;

    @Override
    @Transactional
    public AiSkillResponse createAiSkill(CreateAiSkillRequest request) {
        log.info("Creating new AiSkill with code: {}", request.getSkillCode());
        AiSkill aiSkill = AiSkill.builder()
                .skillCode(request.getSkillCode())
                .skillName(request.getSkillName())
                .description(request.getDescription())
                .build();

        AiSkill savedSkill = aiSkillRepository.save(aiSkill);
        return mapToResponse(savedSkill);
    }

    @Override
    @Transactional
    public AiSkillResponse updateAiSkill(UUID id, UpdateAiSkillRequest request) {
        log.info("Updating AiSkill with id: {}", id);
        AiSkill aiSkill = aiSkillRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AiSkill not found with id: " + id));

        aiSkill.setSkillCode(request.getSkillCode());
        aiSkill.setSkillName(request.getSkillName());
        aiSkill.setDescription(request.getDescription());

        AiSkill updatedSkill = aiSkillRepository.save(aiSkill);
        return mapToResponse(updatedSkill);
    }

    @Override
    @Transactional(readOnly = true)
    public AiSkillResponse getAiSkillById(UUID id) {
        log.info("Fetching AiSkill by id: {}", id);
        AiSkill aiSkill = aiSkillRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AiSkill not found with id: " + id));
        return mapToResponse(aiSkill);
    }

    @Override
    @Transactional(readOnly = true)
    public AiSkillResponse resolveBySkillCode(String skillCode) {
        log.info("Resolving AiSkill by code: {}", skillCode);
        AiSkill aiSkill = aiSkillRepository.findBySkillCode(skillCode)
                .orElseThrow(() -> new ResourceNotFoundException("AiSkill not found with code: " + skillCode));
        return mapToResponse(aiSkill);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponseDto<AiSkillResponse> getAllAiSkills(PageRequestDto pageRequest) {
        log.info("Fetching all AiSkills, page: {}, size: {}", pageRequest.getPage(), pageRequest.getPageSize());

        int page = pageRequest.getPage() > 0 ? pageRequest.getPage() - 1 : 0;
        int size = pageRequest.getPageSize() > 0 ? pageRequest.getPageSize() : 10;

        Sort sort = Sort.unsorted();
        if (pageRequest.getSortBy() != null && !pageRequest.getSortBy().isEmpty()) {
            sort = "desc".equalsIgnoreCase(pageRequest.getSortDir())
                    ? Sort.by(pageRequest.getSortBy()).descending()
                    : Sort.by(pageRequest.getSortBy()).ascending();
        }

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<AiSkill> skillPage = aiSkillRepository.findAll(pageable);

        List<AiSkillResponse> responses = skillPage.getContent().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        return PageResponseDto.<AiSkillResponse>builder()
                .page(skillPage.getNumber() + 1)
                .size(skillPage.getSize())
                .totalElements(skillPage.getTotalElements())
                .totalPages(skillPage.getTotalPages())
                .content(responses)
                .build();
    }

    private AiSkillResponse mapToResponse(AiSkill entity) {
        return AiSkillResponse.builder()
                .id(entity.getId())
                .skillCode(entity.getSkillCode())
                .skillName(entity.getSkillName())
                .description(entity.getDescription())
                .build();
    }
}
