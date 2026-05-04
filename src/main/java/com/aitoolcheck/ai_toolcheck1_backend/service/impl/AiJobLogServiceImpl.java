package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.req.CreateAiJobLogRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobLogResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.rabbitmq.AiTaskMessage;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiSkill;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestResult;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiJobLogRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService;
import com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq.AiTaskProducer;
import jakarta.persistence.EntityManager;
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
    private final EntityManager entityManager;

    @Override
    @Transactional
    public AiJobLogResponse createPendingJobAndTriggerAi(String promptText, String skillCode,
                                                  UUID projectId, UUID sourceFileId) {
        SourceProject projectRef = sourceProjectRepository.getReferenceById(projectId);

        AiJobLog jobLog = AiJobLog.builder()
                .executionStatus(ExecutionStatus.PENDING)
                .startedAt(LocalDateTime.now())
                .jobType(JobType.LEGACY_INFERENCE)
                .modelName("gemini-1.5-flash")
                .sourceProject(projectRef)
                .build();

        AiJobLog savedJob = aiJobLogRepository.save(jobLog);
        log.info("Đã tạo AiJobLog ID: [{}] trạng thái PENDING cho Project: [{}]",
                 savedJob.getId(), projectId);

        AiTaskMessage message = AiTaskMessage.builder()
                .jobId(savedJob.getId().toString())
                .promptText(promptText)
                .skillCode(skillCode)
                .projectId(projectId.toString())
                .sourceFileId(sourceFileId != null ? sourceFileId.toString() : null)
                .build();

        aiTaskProducer.sendAiTask(message);
        log.info("Đã đẩy AiTaskMessage vào RabbitMQ cho Job ID: [{}]", savedJob.getId());

        return mapToResponse(savedJob);
    }

    @Override
    @Transactional
    public AiJobLogResponse createPendingJob(CreateAiJobLogRequest request) {
        log.info("Creating pending AiJobLog of type: {}", request.getJobType());

        AiJobLog jobLog = AiJobLog.builder()
                .executionStatus(ExecutionStatus.PENDING)
                .jobType(request.getJobType())
                .modelName(request.getModelName())
                .tokenInput(request.getTokenInput())
                .tokenOutput(request.getTokenOutput())
                .startedAt(LocalDateTime.now())
                .build();

        if (request.getProjectId() != null) {
            jobLog.setSourceProject(entityManager.getReference(SourceProject.class, request.getProjectId()));
        } else {
            throw new BadRequestException("ProjectId is required to create an AiJobLog");
        }

        if (request.getApiEndpointId() != null) {
            jobLog.setApiEndpoint(entityManager.getReference(ApiEndpoint.class, request.getApiEndpointId()));
        }
        if (request.getTestResultId() != null) {
            jobLog.setTestResult(entityManager.getReference(TestResult.class, request.getTestResultId()));
        }
        if (request.getAiSkillId() != null) {
            jobLog.setAiSkill(entityManager.getReference(AiSkill.class, request.getAiSkillId()));
        }

        AiJobLog savedJob = aiJobLogRepository.save(jobLog);
        log.info("Job {} created with status PENDING", savedJob.getId());
        
        return mapToResponse(savedJob);
    }

    @Override
    @Transactional
    public void markJobAsRunning(UUID id) {
        AiJobLog jobLog = aiJobLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AiJobLog not found with id: " + id));

        if (jobLog.getExecutionStatus() != ExecutionStatus.PENDING) {
            throw new BadRequestException("Job is not in PENDING state");
        }

        jobLog.setExecutionStatus(ExecutionStatus.RUNNING);
        aiJobLogRepository.save(jobLog);
        log.info("Job {} transitioned from PENDING to RUNNING", id);
    }

    @Override
    @Transactional
    public void markJobAsSuccess(UUID id, Integer tokenInput, Integer tokenOutput) {
        AiJobLog jobLog = aiJobLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AiJobLog not found with id: " + id));

        if (jobLog.getExecutionStatus() != ExecutionStatus.RUNNING) {
            throw new BadRequestException("Job must be RUNNING to succeed");
        }

        jobLog.setExecutionStatus(ExecutionStatus.SUCCESS);
        jobLog.setTokenInput(tokenInput);
        jobLog.setTokenOutput(tokenOutput);
        jobLog.setCompletedAt(LocalDateTime.now());
        aiJobLogRepository.save(jobLog);
        log.info("Job {} transitioned from RUNNING to SUCCESS", id);
    }

    @Override
    @Transactional
    public void markJobAsFailed(UUID id, String errorMessage) {
        AiJobLog jobLog = aiJobLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AiJobLog not found with id: " + id));

        jobLog.setExecutionStatus(ExecutionStatus.FAILED);
        jobLog.setErrorMessage(errorMessage);
        jobLog.setCompletedAt(LocalDateTime.now());
        aiJobLogRepository.save(jobLog);
        log.info("Job {} transitioned to FAILED. Reason: {}", id, errorMessage);
    }

    @Override
    @Transactional(readOnly = true)
    public AiJobLogResponse getJobById(UUID id) {
        AiJobLog jobLog = aiJobLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AiJobLog not found with id: " + id));
        return mapToResponse(jobLog);
    }

    private AiJobLogResponse mapToResponse(AiJobLog jobLog) {
        return AiJobLogResponse.builder()
                .id(jobLog.getId())
                .projectId(jobLog.getSourceProject() != null ? jobLog.getSourceProject().getId() : null)
                .apiEndpointId(jobLog.getApiEndpoint() != null ? jobLog.getApiEndpoint().getId() : null)
                .testResultId(jobLog.getTestResult() != null ? jobLog.getTestResult().getId() : null)
                .aiSkillId(jobLog.getAiSkill() != null ? jobLog.getAiSkill().getId() : null)
                .jobType(jobLog.getJobType())
                .modelName(jobLog.getModelName())
                .executionStatus(jobLog.getExecutionStatus())
                .build();
    }
}
