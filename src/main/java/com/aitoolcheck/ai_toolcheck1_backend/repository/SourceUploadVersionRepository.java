package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.SourceUploadVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;

@Repository
public interface SourceUploadVersionRepository extends JpaRepository<SourceUploadVersion, UUID> {

    @Query("select coalesce(max(v.versionNo), 0) from SourceUploadVersion v where v.sourceProject.id = :projectId")
    Integer findMaxVersionNoByProjectId(@Param("projectId") UUID projectId);

    Optional<SourceUploadVersion> findTopBySourceProjectIdOrderByVersionNoDesc(UUID projectId);
}
