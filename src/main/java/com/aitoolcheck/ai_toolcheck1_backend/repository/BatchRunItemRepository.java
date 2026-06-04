package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BatchRunItemStatus;
import com.aitoolcheck.ai_toolcheck1_backend.model.BatchRunItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface BatchRunItemRepository extends JpaRepository<BatchRunItem, UUID> {

    List<BatchRunItem> findByBatchRun_IdOrderByCreatedAtAsc(UUID batchRunId);

    long countByBatchRun_IdAndStatus(UUID batchRunId, BatchRunItemStatus status);

    List<BatchRunItem> findByBatchRun_IdAndStatusInOrderByCreatedAtAsc(
            UUID batchRunId,
            Collection<BatchRunItemStatus> statuses);
}
