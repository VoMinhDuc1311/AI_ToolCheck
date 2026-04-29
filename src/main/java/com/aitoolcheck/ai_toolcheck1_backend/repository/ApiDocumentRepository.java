package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.ApiDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiDocumentRepository extends JpaRepository<ApiDocument, UUID> {

    Optional<ApiDocument> findBySourceProjectId(UUID projectId);

    boolean existsBySourceProjectId(UUID projectId);
}
