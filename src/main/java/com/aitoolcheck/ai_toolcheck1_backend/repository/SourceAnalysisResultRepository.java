package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.SourceAnalysisResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SourceAnalysisResultRepository extends JpaRepository<SourceAnalysisResult, UUID> {

    Optional<SourceAnalysisResult> findBySourceProjectId(UUID projectId);

    @Query("""
            select r
            from SourceAnalysisResult r
            left join fetch r.sourceUploadVersion v
            where r.sourceProject.id = :projectId
            order by r.currentFlag desc, v.versionNo desc, r.id desc
            """)
    List<SourceAnalysisResult> findLatestCandidatesByProjectId(@Param("projectId") UUID projectId);

    boolean existsBySourceProjectId(UUID projectId);
}
