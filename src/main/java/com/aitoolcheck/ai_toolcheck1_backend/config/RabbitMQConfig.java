package com.aitoolcheck.ai_toolcheck1_backend.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String QUEUE_NAME = "ai.task.queue";
    public static final String EXCHANGE_NAME = "ai.task.exchange";
    public static final String ROUTING_KEY = "ai.task.routing.key";

    @Bean
    public Queue aiTaskQueue() {
        // true: durable, queue sẽ tồn tại qua các lần restart RabbitMQ server
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    public DirectExchange aiTaskExchange() {
        return new DirectExchange(EXCHANGE_NAME);
    }

    @Bean
    public Binding bindingAiTaskQueue(Queue aiTaskQueue, DirectExchange aiTaskExchange) {
        return BindingBuilder.bind(aiTaskQueue).to(aiTaskExchange).with(ROUTING_KEY);
    }

    /**
     * RẤT QUAN TRỌNG: Cấu hình MessageConverter sử dụng Jackson 
     * để tự động convert các Java Object sang JSON (khi gửi) và ngược lại (khi nhận).
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
