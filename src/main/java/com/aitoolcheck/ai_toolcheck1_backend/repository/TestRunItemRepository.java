package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.TestRunItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TestRunItemRepository extends JpaRepository<TestRunItem, UUID> {

    List<TestRunItem> findByTestRun_IdOrderBySortOrderAsc(UUID testRunId);

    long countByTestRun_Id(UUID testRunId);
}
