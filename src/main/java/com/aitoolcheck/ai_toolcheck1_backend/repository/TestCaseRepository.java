package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestCaseRepository extends JpaRepository<TestCase, UUID> {

    List<TestCase> findBySourceProject_IdAndDeletedFlagFalseOrderByUpdatedAtDesc(UUID projectId);

    Optional<TestCase> findByIdAndDeletedFlagFalse(UUID id);

    boolean existsBySourceProject_IdAndCaseNameIgnoreCaseAndDeletedFlagFalse(
            UUID projectId,
            String caseName
    );

    boolean existsBySourceProject_IdAndCaseNameIgnoreCaseAndDeletedFlagFalseAndIdNot(
            UUID projectId,
            String caseName,
            UUID id
    );
}