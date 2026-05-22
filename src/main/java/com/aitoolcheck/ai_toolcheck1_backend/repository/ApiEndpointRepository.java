package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ApiEndpointRepository extends JpaRepository<ApiEndpoint, UUID> {

    List<ApiEndpoint> findBySourceProjectId(UUID projectId);

    List<ApiEndpoint> findBySourceProjectIdAndActiveFlagTrue(UUID projectId);

    /**
     * Enrich-trigger candidate query: active (not deleted/inactive) and not stale.
     * Used by triggerEnrichmentForProject to find endpoints eligible for AI enrichment.
     */
    List<ApiEndpoint> findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(UUID projectId);

    @Query("select e from ApiEndpoint e where e.sourceProject.id = :projectId and e.sourceFile.id in :sourceFileIds")
    List<ApiEndpoint> findByProjectIdAndSourceFileIdIn(@Param("projectId") UUID projectId, @Param("sourceFileIds") List<UUID> sourceFileIds);

    @Query("select e from ApiEndpoint e where e.sourceProject.id = :projectId and e.httpMethod = :httpMethod and e.endpointPath = :endpointPath")
    java.util.Optional<ApiEndpoint> findByStableKey(
            @Param("projectId") UUID projectId,
            @Param("httpMethod") com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod httpMethod,
            @Param("endpointPath") String endpointPath
    );

    void deleteBySourceProjectId(UUID projectId);
}
