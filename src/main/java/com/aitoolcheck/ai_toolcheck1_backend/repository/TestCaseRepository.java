package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TestCaseRepository extends JpaRepository<TestCase, UUID> {



    List<TestCase> findBySourceProject_IdAndDeletedFlagFalseOrderByUpdatedAtDesc(UUID projectId);

    Optional<TestCase> findByIdAndDeletedFlagFalse(UUID id);

    boolean existsBySourceProject_IdAndCaseNameIgnoreCaseAndDeletedFlagFalse(UUID projectId, String caseName);

    boolean existsBySourceProject_IdAndCaseNameIgnoreCaseAndDeletedFlagFalseAndIdNot(UUID projectId, String caseName, UUID id);
    List<TestCase> findBySourceProject_IdAndActiveFlagTrueAndDeletedFlagFalseOrderByUpdatedAtDesc(UUID projectId);
    List<TestCase> findByIdInAndSourceProject_IdAndActiveFlagTrueAndDeletedFlagFalse(Collection<UUID> ids, UUID projectId);
}