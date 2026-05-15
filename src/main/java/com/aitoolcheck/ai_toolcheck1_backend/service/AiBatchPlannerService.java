package com.aitoolcheck.ai_toolcheck1_backend.service;

import java.util.List;
import java.util.function.Function;

public interface AiBatchPlannerService {
    <T> List<List<T>> splitByMaxItemsAndEstimatedChars(
            List<T> items,
            Function<T, String> serializer,
            int maxItemsPerBatch,
            int maxCharsPerBatch
    );
}
