package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.TestCaseInput;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TestCaseInputRepository extends JpaRepository<TestCaseInput, UUID> {
    Optional<TestCaseInput> findByTestCase_Id(UUID testCaseId);
    boolean existsByTestCase_Id(UUID testCaseId);
    void deleteByTestCase_Id(UUID testCaseId);
}
