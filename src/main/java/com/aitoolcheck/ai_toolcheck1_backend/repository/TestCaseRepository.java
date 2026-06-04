package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TestCaseRepository extends JpaRepository<TestCase, UUID>, JpaSpecificationExecutor<TestCase> {

    List<TestCase> findBySourceProject_IdAndDeletedFlagFalseOrderByUpdatedAtDesc(UUID projectId);

    Optional<TestCase> findByIdAndDeletedFlagFalse(UUID id);

    boolean existsBySourceProject_IdAndCaseNameIgnoreCaseAndDeletedFlagFalse(UUID projectId, String caseName);

    boolean existsBySourceProject_IdAndCaseNameIgnoreCaseAndDeletedFlagFalseAndIdNot(UUID projectId, String caseName, UUID id);

    List<TestCase> findBySourceProject_IdAndActiveFlagTrueAndDeletedFlagFalseOrderByUpdatedAtDesc(UUID projectId);

    List<TestCase> findByIdInAndSourceProject_IdAndActiveFlagTrueAndDeletedFlagFalse(Collection<UUID> ids, UUID projectId);

    long countBySourceProject_IdAndDeletedFlagFalse(UUID projectId);

    long countBySourceProject_IdAndActiveFlagTrueAndDeletedFlagFalse(UUID projectId);

    long countBySourceProject_IdAndGeneratedByAndDeletedFlagFalse(UUID projectId, com.aitoolcheck.ai_toolcheck1_backend.enums.GeneratedBy generatedBy);

    long countBySourceProject_IdAndGeneratedByAndActiveFlagTrueAndDeletedFlagFalse(
            UUID projectId,
            com.aitoolcheck.ai_toolcheck1_backend.enums.GeneratedBy generatedBy);

    // ── Permanent delete support ───────────────────────────────────────────────

    /** Delete all TestCase rows for a project (after assertions/inputs already deleted). */
    @Modifying
    @Query("DELETE FROM TestCase tc WHERE tc.sourceProject.id = :projectId")
    void deleteBySourceProjectId(@Param("projectId") UUID projectId);
}
