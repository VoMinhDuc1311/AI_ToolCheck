package com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq;

import com.aitoolcheck.ai_toolcheck1_backend.config.RabbitMQConfig;
import com.aitoolcheck.ai_toolcheck1_backend.dto.rabbitmq.AiTaskMessage;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiJobLogRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.GeminiApiClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiTaskConsumer {

    private final GeminiApiClientService geminiApiClientService;
    private final AiJobLogRepository aiJobLogRepository; 

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
    public void receiveAiTask(AiTaskMessage message) {
        log.info("Consumer: Received AI Task Message - JobId: {}, SkillCode: {}", 
                message.getJobId(), message.getSkillCode());

        AiJobLog jobLog = null;
        try {
            // Lấy lại AiJobLog từ DB
            UUID jobId = UUID.fromString(message.getJobId());
            jobLog = aiJobLogRepository.findById(jobId)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy JobLog với ID: " + jobId));

            // Cập nhật status thành RUNNING
            log.info("=> Updating job status to RUNNING for JobId: {}", message.getJobId());
            jobLog.setExecutionStatus(ExecutionStatus.RUNNING);
            aiJobLogRepository.save(jobLog);

            // Gọi Gemini API (Sử dụng .block() để chờ kết quả do môi trường Listener này là blocking).
            log.info("=> Calling Gemini API via WebClient...");
            String result = geminiApiClientService.sendPrompt(message.getPromptText()).block();

            // Cập nhật status thành SUCCESS
            log.info("=> Gemini API Call SUCCESS. Saving result for JobId: {}", message.getJobId());
            jobLog.setExecutionStatus(ExecutionStatus.SUCCESS);
            jobLog.setCompletedAt(LocalDateTime.now());
            // TODO: Lưu result vào bảng liên quan nếu cần
            aiJobLogRepository.save(jobLog);

        } catch (Exception e) {
            // Bọc try-catch toàn bộ, nếu lỗi cập nhật status thành FAILED
            log.error("=> Error occurred while processing AI Task for JobId: {}. Exception: {}", 
                    message.getJobId(), e.getMessage(), e);

            // Nếu lấy được jobLog (có tồn tại trong DB), thì lưu lại lỗi
            if (jobLog != null) {
                try {
                    jobLog.setExecutionStatus(ExecutionStatus.FAILED);
                    jobLog.setErrorMessage(e.getMessage());
                    jobLog.setCompletedAt(LocalDateTime.now());
                    aiJobLogRepository.save(jobLog);
                    log.info("=> Saved FAILED status to DB for JobId: {}", jobLog.getId());
                } catch (Exception dbEx) {
                    log.error("=> Failed to save FAILED status to DB for JobId: {}", jobLog.getId(), dbEx);
                }
            }
            
            // KHÔNG throw exception ra ngoài để RabbitMQ tự động ACK message này
            // (Xóa message khỏi queue vì đã ghi nhận FAILED vào database)
        }
    }
}
