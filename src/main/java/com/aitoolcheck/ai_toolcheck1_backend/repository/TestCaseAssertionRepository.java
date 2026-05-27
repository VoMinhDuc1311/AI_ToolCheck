package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseAssertion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TestCaseAssertionRepository extends JpaRepository<TestCaseAssertion, UUID> {

    List<TestCaseAssertion> findByTestCase_IdOrderBySortOrderAsc(UUID testCaseId);

    void deleteByTestCase_Id(UUID testCaseId);

    List<TestCaseAssertion> findByTestCase_IdIn(java.util.Collection<UUID> testCaseIds);

    // ── Permanent delete support ───────────────────────────────────────────────

    /** Delete all TestCaseAssertion rows for a project. */
    @Modifying
    @Query("""
        DELETE FROM TestCaseAssertion tca
        WHERE tca.testCase.id IN (
            SELECT tc.id FROM TestCase tc WHERE tc.sourceProject.id = :projectId
        )
    """)
    void deleteByTestCaseProjectId(@Param("projectId") UUID projectId);

    @Query("SELECT COUNT(tca) FROM TestCaseAssertion tca WHERE tca.testCase.sourceProject.id = :projectId AND tca.testCase.deletedFlag = false")
    long countAssertionsByProjectId(@Param("projectId") UUID projectId);
}
