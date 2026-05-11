package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SourceFileRepository extends JpaRepository<SourceFile, UUID> {

    List<SourceFile> findBySourceProjectId(UUID projectId);

    List<SourceFile> findBySourceProjectIdAndActiveFlagTrue(UUID projectId);

    void deleteBySourceProjectId(UUID projectId);

    @Query("select f from SourceFile f where f.sourceProject.id = :projectId and f.filePath in :filePaths")
    List<SourceFile> findByProjectIdAndFilePathIn(@Param("projectId") UUID projectId, @Param("filePaths") List<String> filePaths);
}
