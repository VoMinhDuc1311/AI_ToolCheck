package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.LegacyInferenceLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface LegacyInferenceLogRepository extends JpaRepository<LegacyInferenceLog, UUID> {

    // ── Permanent delete support ───────────────────────────────────────────────

    /** Delete all LegacyInferenceLog rows for a project. */
    @Modifying
    @Query("DELETE FROM LegacyInferenceLog l WHERE l.sourceProject.id = :projectId")
    void deleteBySourceProjectId(@Param("projectId") UUID projectId);
}
