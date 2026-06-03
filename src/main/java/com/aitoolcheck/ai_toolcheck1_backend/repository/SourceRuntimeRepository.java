package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceRuntime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SourceRuntimeRepository extends JpaRepository<SourceRuntime, UUID> {

    Optional<SourceRuntime> findFirstBySourceProject_IdOrderByUpdatedAtDesc(UUID projectId);

    long countByRuntimeStatus(RuntimeStatus runtimeStatus);
}
