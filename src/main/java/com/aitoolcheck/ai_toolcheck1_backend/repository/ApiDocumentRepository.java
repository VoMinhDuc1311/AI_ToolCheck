package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.ApiDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiDocumentRepository extends JpaRepository<ApiDocument, UUID> {

    Optional<ApiDocument> findBySourceProjectId(UUID projectId);

    boolean existsBySourceProjectId(UUID projectId);

    // ── Permanent delete support ───────────────────────────────────────────────

    /** Delete the ApiDocument for a project (after versions already deleted). */
    @Modifying
    @Query("DELETE FROM ApiDocument ad WHERE ad.sourceProject.id = :projectId")
    void deleteBySourceProjectId(@Param("projectId") UUID projectId);
}
