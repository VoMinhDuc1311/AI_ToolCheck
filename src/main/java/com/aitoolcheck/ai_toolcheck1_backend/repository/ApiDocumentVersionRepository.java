package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.ApiDocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiDocumentVersionRepository extends JpaRepository<ApiDocumentVersion, UUID> {

    List<ApiDocumentVersion> findByApiDocumentId(UUID apiDocumentId);

    Optional<ApiDocumentVersion> findTopByApiDocumentIdOrderByVersionNoDesc(UUID apiDocumentId);

    List<ApiDocumentVersion> findByApiDocumentSourceProjectId(UUID projectId);
}
