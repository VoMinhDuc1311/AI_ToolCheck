package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.realtime.TestRunRealtimeEvent;

public interface TestRunRealtimePublisher {

    void publishRunStarted(TestRunRealtimeEvent event);

    void publishItemStarted(TestRunRealtimeEvent event);

    void publishItemCompleted(TestRunRealtimeEvent event);

    void publishRunCompleted(TestRunRealtimeEvent event);

    void publishRunFailed(TestRunRealtimeEvent event);
}
