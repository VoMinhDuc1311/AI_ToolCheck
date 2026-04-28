package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.SourceAnalysisResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SourceAnalysisResultRepository extends JpaRepository<SourceAnalysisResult, UUID> {

    Optional<SourceAnalysisResult> findBySourceProjectId(UUID projectId);

    boolean existsBySourceProjectId(UUID projectId);
}