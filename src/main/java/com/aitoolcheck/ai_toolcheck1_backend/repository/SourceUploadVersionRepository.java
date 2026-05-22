package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.SourceUploadVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SourceUploadVersionRepository extends JpaRepository<SourceUploadVersion, UUID> {

    @Query("select coalesce(max(v.versionNo), 0) from SourceUploadVersion v where v.sourceProject.id = :projectId")
    Integer findMaxVersionNoByProjectId(@Param("projectId") UUID projectId);

    Optional<SourceUploadVersion> findTopBySourceProjectIdOrderByVersionNoDesc(UUID projectId);

    // ── Permanent delete support ───────────────────────────────────────────────

    /**
     * Delete all SourceUploadVersion rows for a project.
     * Must run AFTER source_file is deleted (source_file has FK to source_upload_version).
     */
    @Modifying
    @Query("DELETE FROM SourceUploadVersion suv WHERE suv.sourceProject.id = :projectId")
    void deleteBySourceProjectId(@Param("projectId") UUID projectId);
}
