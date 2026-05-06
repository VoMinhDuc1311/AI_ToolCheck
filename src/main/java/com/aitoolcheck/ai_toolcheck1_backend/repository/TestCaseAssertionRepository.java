package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseAssertion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TestCaseAssertionRepository extends JpaRepository<TestCaseAssertion, UUID> {
    List<TestCaseAssertion> findByTestCase_IdOrderBySortOrderAsc(UUID testCaseId);

    void deleteByTestCase_Id(UUID testCaseId);
}
