package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.ApiDocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiDocumentVersionRepository extends JpaRepository<ApiDocumentVersion, UUID> {

    List<ApiDocumentVersion> findByApiDocumentId(UUID apiDocumentId);

    List<ApiDocumentVersion> findByApiDocumentIdOrderByVersionNoDesc(UUID apiDocumentId);

    Optional<ApiDocumentVersion> findTopByApiDocumentIdOrderByVersionNoDesc(UUID apiDocumentId);

    List<ApiDocumentVersion> findByApiDocumentSourceProjectId(UUID projectId);

    List<ApiDocumentVersion> findByApiDocumentSourceProjectIdOrderByVersionNoDesc(UUID projectId);

    Optional<ApiDocumentVersion> findByApiDocumentIdAndVersionNo(UUID apiDocumentId, Integer versionNo);

    boolean existsByApiDocumentIdAndVersionNo(UUID apiDocumentId, Integer versionNo);

    long countByApiDocumentId(UUID apiDocumentId);

    // ── Permanent delete support ───────────────────────────────────────────────

    /**
     * Delete all ApiDocumentVersion rows for a project.
     * Must run before deleting ApiDocument.
     */
    @Modifying
    @Query("""
        DELETE FROM ApiDocumentVersion adv
        WHERE adv.apiDocument.id IN (
            SELECT ad.id FROM ApiDocument ad WHERE ad.sourceProject.id = :projectId
        )
    """)
    void deleteByApiDocumentProjectId(@Param("projectId") UUID projectId);
}