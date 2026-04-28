package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.rabbitmq.AiTaskMessage;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiJobLogRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService;
import com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq.AiTaskProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiJobLogServiceImpl implements AiJobLogService {

    private final AiJobLogRepository aiJobLogRepository;
    private final AiTaskProducer aiTaskProducer;

    @Override
    @Transactional
    public AiJobLog createPendingJobAndTriggerAi(String promptText, String skillCode) {
        // Bước 1: Khởi tạo đối tượng AiJobLog mới với trạng thái PENDING
        AiJobLog jobLog = AiJobLog.builder()
                .executionStatus(ExecutionStatus.PENDING)
                .startedAt(LocalDateTime.now())
                .build();

        // Bước 2: Lưu vào DB và lấy ID tự sinh
        AiJobLog savedJob = aiJobLogRepository.save(jobLog);
        log.info("Đã tạo thành công AiJobLog với ID: [{}] và trạng thái PENDING", savedJob.getId());

        // Bước 3: Tạo AiTaskMessage
        AiTaskMessage message = AiTaskMessage.builder()
                .jobId(savedJob.getId().toString())
                .promptText(promptText)
                .skillCode(skillCode)
                .build();

        // Bước 4: Đẩy message vào RabbitMQ
        aiTaskProducer.sendAiTask(message);
        log.info("Đã đẩy thành công AiTaskMessage vào RabbitMQ cho Job ID: [{}]", savedJob.getId());

        // Bước 5: Trả về đối tượng đã lưu
        return savedJob;
    }
}
