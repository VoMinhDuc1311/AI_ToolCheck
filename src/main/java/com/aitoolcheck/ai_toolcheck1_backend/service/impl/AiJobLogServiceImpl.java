package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.rabbitmq.AiTaskMessage;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiJobLogRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService;
import com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq.AiTaskProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiJobLogServiceImpl implements AiJobLogService {

    private final AiJobLogRepository aiJobLogRepository;
    private final AiTaskProducer aiTaskProducer;
    private final SourceProjectRepository sourceProjectRepository;

    @Override
    @Transactional
    public AiJobLog createPendingJobAndTriggerAi(String promptText, String skillCode,
                                                  UUID projectId, UUID sourceFileId) {
        // Bước 1: Lấy SourceProject Reference (không tốn SELECT thêm)
        SourceProject projectRef = sourceProjectRepository.getReferenceById(projectId);

        // Bước 2: Khởi tạo AiJobLog với trạng thái PENDING, gắn project
        AiJobLog jobLog = AiJobLog.builder()
                .executionStatus(ExecutionStatus.PENDING)
                .startedAt(LocalDateTime.now())
                .sourceProject(projectRef)   // ← Liên kết với SourceProject của Dev A
                .build();

        AiJobLog savedJob = aiJobLogRepository.save(jobLog);
        log.info("Đã tạo AiJobLog ID: [{}] trạng thái PENDING cho Project: [{}]",
                 savedJob.getId(), projectId);

        // Bước 3: Build message — truyền projectId + sourceFileId cho Consumer
        AiTaskMessage message = AiTaskMessage.builder()
                .jobId(savedJob.getId().toString())
                .promptText(promptText)
                .skillCode(skillCode)
                .projectId(projectId.toString())                                    // ← Audit Trail
                .sourceFileId(sourceFileId != null ? sourceFileId.toString() : null) // ← FK của Dev A
                .build();

        aiTaskProducer.sendAiTask(message);
        log.info("Đã đẩy AiTaskMessage vào RabbitMQ cho Job ID: [{}]", savedJob.getId());

        return savedJob;
    }
}
