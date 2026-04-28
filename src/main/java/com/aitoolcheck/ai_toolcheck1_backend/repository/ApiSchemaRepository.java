package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.ApiSchema;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiSchemaRepository extends JpaRepository<ApiSchema, UUID> {

    List<ApiSchema> findBySourceProjectId(UUID projectId);

    Optional<ApiSchema> findBySourceProjectIdAndSchemaName(UUID projectId, String schemaName);

    void deleteBySourceProjectId(UUID projectId);
}
