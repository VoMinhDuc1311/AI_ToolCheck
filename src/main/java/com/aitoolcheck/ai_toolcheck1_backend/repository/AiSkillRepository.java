package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.AiSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AiSkillRepository extends JpaRepository<AiSkill, UUID> {
    Optional<AiSkill> findBySkillCode(String skillCode);
    boolean existsBySkillCode(String skillCode);
}
