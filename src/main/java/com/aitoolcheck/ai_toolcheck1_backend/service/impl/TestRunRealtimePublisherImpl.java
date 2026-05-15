package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.realtime.TestRunRealtimeEvent;
import com.aitoolcheck.ai_toolcheck1_backend.enums.TestRunRealtimeEventType;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestRunRealtimePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestRunRealtimePublisherImpl implements TestRunRealtimePublisher {

    private static final String TOPIC_PATTERN = "/topic/projects/%s/test-runs/%s";

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void publishRunStarted(TestRunRealtimeEvent event) {
        safePublish(event, TestRunRealtimeEventType.RUN_STARTED);
    }

    @Override
    public void publishItemStarted(TestRunRealtimeEvent event) {
        safePublish(event, TestRunRealtimeEventType.ITEM_STARTED);
    }

    @Override
    public void publishItemCompleted(TestRunRealtimeEvent event) {
        safePublish(event, TestRunRealtimeEventType.ITEM_COMPLETED);
    }

    @Override
    public void publishRunCompleted(TestRunRealtimeEvent event) {
        safePublish(event, TestRunRealtimeEventType.RUN_COMPLETED);
    }

    @Override
    public void publishRunFailed(TestRunRealtimeEvent event) {
        safePublish(event, TestRunRealtimeEventType.RUN_FAILED);
    }

    private void safePublish(TestRunRealtimeEvent event, TestRunRealtimeEventType eventType) {
        try {
            if (event == null) {
                log.warn("[TestRunRealtimePublisher] Skipping {} publish: event is null", eventType);
                return;
            }

            UUID projectId = event.getProjectId();
            UUID testRunId = event.getTestRunId();
            if (projectId == null || testRunId == null) {
                log.warn(
                        "[TestRunRealtimePublisher] Skipping {} publish: projectId={}, testRunId={}",
                        eventType,
                        projectId,
                        testRunId
                );
                return;
            }

            if (!hasText(event.getEventType())) {
                event.setEventType(eventType.name());
            }

            if (event.getOccurredAt() == null) {
                event.setOccurredAt(LocalDateTime.now());
            }

            String topic = buildTopic(projectId, testRunId);
            messagingTemplate.convertAndSend(topic, event);
            log.debug(
                    "[TestRunRealtimePublisher] Published {} to projectId={}, testRunId={}",
                    eventType,
                    projectId,
                    testRunId
            );
        } catch (Exception ex) {
            UUID projectId = event != null ? event.getProjectId() : null;
            UUID testRunId = event != null ? event.getTestRunId() : null;
            log.warn(
                    "[TestRunRealtimePublisher] Failed to publish {} for projectId={}, testRunId={}: {}",
                    eventType,
                    projectId,
                    testRunId,
                    ex.getMessage()
            );
        }
    }

    private String buildTopic(UUID projectId, UUID testRunId) {
        return TOPIC_PATTERN.formatted(projectId, testRunId);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
