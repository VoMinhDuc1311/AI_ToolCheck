package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TestCaseInputRepository extends JpaRepository<TestCaseInput, UUID> {

    Optional<TestCaseInput> findByTestCase_Id(UUID testCaseId);

    boolean existsByTestCase_Id(UUID testCaseId);

    void deleteByTestCase_Id(UUID testCaseId);

    // ── Permanent delete support ───────────────────────────────────────────────

    /** Delete all TestCaseInput rows for a project. */
    @Modifying
    @Query("""
        DELETE FROM TestCaseInput tci
        WHERE tci.testCase.id IN (
            SELECT tc.id FROM TestCase tc WHERE tc.sourceProject.id = :projectId
        )
    """)
    void deleteByTestCaseProjectId(@Param("projectId") UUID projectId);
}
