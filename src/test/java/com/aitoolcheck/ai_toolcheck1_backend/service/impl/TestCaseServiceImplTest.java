package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.AiOptimizationProperties;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCase;
import com.aitoolcheck.ai_toolcheck1_backend.repository.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJsonParserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiModelRouterService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiPayloadOptimizerService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq.AiTaskProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCaseServiceImplTest {

    @Test
    void deleteUsesProjectLevelDeleteGuard() {
        TestCaseRepository testCaseRepository = mock(TestCaseRepository.class);
        ProjectAccessService projectAccessService = mock(ProjectAccessService.class);
        UUID testCaseId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        SourceProject project = new SourceProject();
        project.setId(projectId);
        TestCase testCase = new TestCase();
        testCase.setId(testCaseId);
        testCase.setSourceProject(project);
        testCase.setDeletedFlag(false);

        when(testCaseRepository.findByIdAndDeletedFlagFalse(testCaseId)).thenReturn(Optional.of(testCase));

        TestCaseServiceImpl service = new TestCaseServiceImpl(
                testCaseRepository,
                mock(ApiEndpointRepository.class),
                mock(ApiDocumentVersionRepository.class),
                mock(AiJobLogRepository.class),
                mock(AiTaskProducer.class),
                mock(AiModelRouterService.class),
                mock(AiJsonParserService.class),
                projectAccessService,
                mock(AiSkillRepository.class),
                new ObjectMapper(),
                mock(ApplicationContext.class),
                mock(AiPayloadOptimizerService.class),
                mock(AiOptimizationProperties.class));

        service.delete(testCaseId);

        verify(projectAccessService).requireCanDeleteTestCase(projectId);
        verify(testCaseRepository).save(testCase);
    }
}
