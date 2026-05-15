package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.service.AiBatchPlannerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Slf4j
@Service
public class AiBatchPlannerServiceImpl implements AiBatchPlannerService {

    @Override
    public <T> List<List<T>> splitByMaxItemsAndEstimatedChars(
            List<T> items,
            Function<T, String> serializer,
            int maxItemsPerBatch,
            int maxCharsPerBatch) {
        
        List<List<T>> batches = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return batches;
        }

        List<T> currentBatch = new ArrayList<>();
        int currentBatchChars = 0;

        for (T item : items) {
            String serialized = serializer.apply(item);
            int itemChars = serialized != null ? serialized.length() : 0;

            if (itemChars > maxCharsPerBatch) {
                log.warn("[AiBatchPlanner] Một item đơn lẻ có kích thước {} vượt quá maxCharsPerBatch {}. Đưa vào batch riêng.", itemChars, maxCharsPerBatch);
            }

            // Nếu thêm item này vào batch hiện tại mà vượt quá giới hạn (item count hoặc size) thì ngắt batch
            if (!currentBatch.isEmpty() && 
               (currentBatch.size() >= maxItemsPerBatch || currentBatchChars + itemChars > maxCharsPerBatch)) {
                batches.add(new ArrayList<>(currentBatch));
                currentBatch.clear();
                currentBatchChars = 0;
            }

            currentBatch.add(item);
            currentBatchChars += itemChars;
        }

        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }

        log.info("[AiBatchPlanner] Đã chia {} items thành {} batches", items.size(), batches.size());
        return batches;
    }
}
