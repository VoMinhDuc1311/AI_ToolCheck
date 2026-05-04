package com.aitoolcheck.ai_toolcheck1_backend.config.init;

import com.aitoolcheck.ai_toolcheck1_backend.model.AiSkill;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiSkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiSkillDataSeeder implements CommandLineRunner {

    private final AiSkillRepository aiSkillRepository;

    @Override
    public void run(String... args) throws Exception {
        log.info("Starting AiSkillDataSeeder...");

        List<AiSkill> defaultSkills = List.of(
                AiSkill.builder().skillCode("legacy_code_reader").skillName("Legacy Code Reader").description("Reads and extracts endpoints from legacy Java code without annotations.").build(),
                AiSkill.builder().skillCode("enrich_api_doc").skillName("Enrich API Documentation").description("Generates summary, description, and mock JSON for API Endpoints.").build(),
                AiSkill.builder().skillCode("generate_testcases").skillName("Generate Test Cases").description("Generates test cases from OpenAPI specifications.").build(),
                AiSkill.builder().skillCode("analyze_test_result").skillName("Analyze Test Result").description("Analyzes failed test executions and suggests fixes.").build()
        );

        int count = 0;
        for (AiSkill skill : defaultSkills) {
            if (!aiSkillRepository.existsBySkillCode(skill.getSkillCode())) {
                aiSkillRepository.save(skill);
                log.info("Seeded default AiSkill: {}", skill.getSkillCode());
                count++;
            }
        }

        log.info("AiSkillDataSeeder finished. Seeded {} new skills.", count);
    }
}
