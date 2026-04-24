package com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq;

import com.aitoolcheck.ai_toolcheck1_backend.config.RabbitMQConfig;
import com.aitoolcheck.ai_toolcheck1_backend.dto.rabbitmq.AiTaskMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiTaskProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * Gửi message tới RabbitMQ Exchange.
     * Jackson2JsonMessageConverter (được cấu hình ở RabbitMQConfig) sẽ tự động biến message thành JSON.
     *
     * @param message Đối tượng chứa thông tin task AI
     */
    public void sendAiTask(AiTaskMessage message) {
        log.info("Producer: Sending AI Task to RabbitMQ - JobId: {}", message.getJobId());
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_NAME, 
                RabbitMQConfig.ROUTING_KEY, 
                message
        );
    }
}
