package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.ApiSchemaField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ApiSchemaFieldRepository extends JpaRepository<ApiSchemaField, UUID> {

    List<ApiSchemaField> findByApiSchemaId(UUID apiSchemaId);

    void deleteByApiSchema_SourceProject_Id(UUID projectId);
}
