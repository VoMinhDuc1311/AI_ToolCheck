package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.TestRunStaleProperties;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RunStatus;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestRun;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestCaseAssertionRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestCaseRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestFailureAnalysisRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestResultRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestRunItemRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.TestRunRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuleEngineService;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceRuntimeService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestResultService;
import com.aitoolcheck.ai_toolcheck1_backend.service.TestRunRealtimePublisher;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.notification.ProjectNotificationEventPublisher;
import com.aitoolcheck.ai_toolcheck1_backend.service.runner.TestHttpExecutor;
import com.aitoolcheck.ai_toolcheck1_backend.service.runner.TestRequestBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestRunStaleGuardTest {

    private TestRunRepository testRunRepository;
    private TestRunItemRepository testRunItemRepository;
    private TestRunServiceImpl service;

    @BeforeEach
    void setUp() {
        testRunRepository = mock(TestRunRepository.class);
        testRunItemRepository = mock(TestRunItemRepository.class);
        TestRunStaleProperties staleProperties = new TestRunStaleProperties();
        staleProperties.setPendingTimeoutMinutes(30);
        staleProperties.setRunningTimeoutMinutes(120);

        service = new TestRunServiceImpl(
                testRunRepository,
                testRunItemRepository,
                mock(TestCaseRepository.class),
                mock(TestCaseAssertionRepository.class),
                mock(SourceProjectRepository.class),
                mock(TestResultRepository.class),
                mock(ApiEndpointRepository.class),
                mock(TestRequestBuilder.class),
                mock(TestHttpExecutor.class),
                new ObjectMapper(),
                mock(TestResultService.class),
                mock(ProjectAccessService.class),
                mock(RuleEngineService.class),
                mock(TransactionTemplate.class),
                mock(TestRunRealtimePublisher.class),
                mock(ProjectNotificationEventPublisher.class),
                mock(TestFailureAnalysisRepository.class),
                staleProperties,
                mock(SourceRuntimeService.class)
        );
    }

    @Test
    void stalePendingRun_marksFailedWithReason() {
        TestRun pending = run(RunStatus.PENDING, LocalDateTime.now().minusMinutes(31));
        when(testRunRepository.findByRunStatusAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(pending))
                .thenReturn(List.of());

        int updated = service.markStaleRunsFailed();

        assertThat(updated).isEqualTo(1);
        assertThat(pending.getRunStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(pending.getDescription()).contains("Test run expired while pending");
    }

    @Test
    void staleRunningRun_marksFailedWithReason() {
        TestRun running = run(RunStatus.RUNNING, LocalDateTime.now().minusMinutes(121));
        when(testRunRepository.findByRunStatusAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of())
                .thenReturn(List.of(running));

        int updated = service.markStaleRunsFailed();

        assertThat(updated).isEqualTo(1);
        assertThat(running.getRunStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(running.getDescription()).contains("exceeded maximum execution time");
    }

    @Test
    void nonStalePendingRun_remainsPending() {
        when(testRunRepository.findByRunStatusAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of())
                .thenReturn(List.of());

        int updated = service.markStaleRunsFailed();

        assertThat(updated).isZero();
        verify(testRunRepository, never()).save(any());
    }

    @Test
    void executeStalePendingRun_doesNotRemainPending() {
        TestRun pending = run(RunStatus.PENDING, LocalDateTime.now().minusMinutes(31));
        when(testRunRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.execute(pending.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("expired while pending");

        assertThat(pending.getRunStatus()).isEqualTo(RunStatus.FAILED);
        verify(testRunRepository).save(pending);
    }

    private TestRun run(RunStatus status, LocalDateTime createdAt) {
        SourceProject project = new SourceProject();
        project.setId(UUID.randomUUID());
        TestRun run = new TestRun();
        run.setId(UUID.randomUUID());
        run.setSourceProject(project);
        run.setRunStatus(status);
        run.setRunName("Test Run");
        run.setBaseUrl("http://localhost:8080");
        run.setCreatedAt(createdAt);
        run.setUpdatedAt(createdAt);
        return run;
    }
}
