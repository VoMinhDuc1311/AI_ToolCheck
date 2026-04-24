package com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq;

import com.aitoolcheck.ai_toolcheck1_backend.config.RabbitMQConfig;
import com.aitoolcheck.ai_toolcheck1_backend.dto.rabbitmq.AiTaskMessage;
import com.aitoolcheck.ai_toolcheck1_backend.service.GeminiApiClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiTaskConsumer {

    private final GeminiApiClientService geminiApiClientService;
    
    // TODO: Bỏ comment dòng dưới khi đã tạo sẵn interface AiJobLogRepository
    // private final AiJobLogRepository aiJobLogRepository; 

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
    public void receiveAiTask(AiTaskMessage message) {
        // 1. Log thông tin nhận được message.
        log.info("Consumer: Received AI Task Message - JobId: {}, SkillCode: {}", 
                message.getJobId(), message.getSkillCode());

        try {
            // 2. (Giả lập) Cập nhật status AiJobLog thành RUNNING.
            log.info("=> Updating job status to RUNNING for JobId: {}", message.getJobId());
            // aiJobLogRepository.updateStatus(message.getJobId(), "RUNNING");

            // 3. Gọi Gemini API (Sử dụng .block() để chờ kết quả do môi trường Listener này là blocking).
            log.info("=> Calling Gemini API via WebClient...");
            String result = geminiApiClientService.sendPrompt(message.getPromptText()).block();

            // 4. (Giả lập) Cập nhật status AiJobLog thành SUCCESS, lưu kết quả AI.
            log.info("=> Gemini API Call SUCCESS. Saving result for JobId: {}", message.getJobId());
            // aiJobLogRepository.updateStatusAndResult(message.getJobId(), "SUCCESS", result);

        } catch (Exception e) {
            // 5. Bọc try-catch toàn bộ, nếu lỗi cập nhật status thành FAILED.
            log.error("=> Error occurred while processing AI Task for JobId: {}. Exception: {}", 
                    message.getJobId(), e.getMessage(), e);
            // aiJobLogRepository.updateStatus(message.getJobId(), "FAILED");
            
            // Lưu ý: Tùy theo logic nghiệp vụ, có thể throw exception ở đây 
            // để cấu hình tự động đẩy message vào Dead Letter Queue (DLQ) của RabbitMQ
        }
    }
}
