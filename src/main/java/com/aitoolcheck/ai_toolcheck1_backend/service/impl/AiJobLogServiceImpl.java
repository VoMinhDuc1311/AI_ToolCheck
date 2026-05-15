package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.req.CreateAiJobLogRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobLogResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.rabbitmq.AiTaskMessage;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.JobType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ForbiddenException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiSkill;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestResult;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiJobLogRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.CurrentUserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq.AiTaskProducer;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiSkillRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiJobLogServiceImpl implements AiJobLogService {

    private final AiJobLogRepository aiJobLogRepository;
    private final AiTaskProducer aiTaskProducer;
    private final SourceProjectRepository sourceProjectRepository;
    private final EntityManager entityManager;
    private final AiSkillRepository aiSkillRepository;
    private final ApiEndpointRepository apiEndpointRepository;
    private final ProjectAccessService projectAccessService;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional
    public AiJobLogResponse createPendingJobAndTriggerAi(String promptText, String skillCode,
                                                  UUID projectId, UUID sourceFileId, UUID apiEndpointId) {
        projectAccessService.requireCanTriggerAiJob(projectId);
        SourceProject projectRef = sourceProjectRepository.getReferenceById(projectId);

        AiSkill aiSkill = aiSkillRepository.findBySkillCode(skillCode)
                .orElseThrow(() -> new BadRequestException("SkillCode không hợp lệ: " + skillCode));

        JobType jobType;
        switch (skillCode.toLowerCase()) {
            case "legacy_code_reader":
                jobType = JobType.LEGACY_INFERENCE;
                break;
            case "enrich_api_doc":
                jobType = JobType.DOCUMENT_ENRICHMENT;
                break;
            case "generate_testcases":
                jobType = JobType.TEST_CASE_GENERATION;
                break;
            case "analyze_test_result":
                jobType = JobType.FAILURE_ANALYSIS;
                break;
            default:
                jobType = JobType.LEGACY_INFERENCE;
        }

        AiJobLog jobLog = AiJobLog.builder()
                .executionStatus(ExecutionStatus.PENDING)
                .startedAt(LocalDateTime.now())
                .jobType(jobType)
                .modelName("gemini-1.5-flash")
                .sourceProject(projectRef)
                .aiSkill(aiSkill)
                .build();
                
        if (apiEndpointId != null) {
            jobLog.setApiEndpoint(entityManager.getReference(ApiEndpoint.class, apiEndpointId));
        }

        AiJobLog savedJob = aiJobLogRepository.save(jobLog);
        log.info("Đã tạo AiJobLog ID: [{}] trạng thái PENDING cho Project: [{}]",
                 savedJob.getId(), projectId);

        AiTaskMessage message = AiTaskMessage.builder()
                .jobId(savedJob.getId().toString())
                .promptText(promptText)
                .skillCode(skillCode)
                .projectId(projectId.toString())
                .sourceFileId(sourceFileId != null ? sourceFileId.toString() : null)
                .apiEndpointId(apiEndpointId != null ? apiEndpointId.toString() : null)
                .build();

        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    aiTaskProducer.sendAiTask(message);
                    log.info("Đã đẩy AiTaskMessage vào RabbitMQ cho Job ID: [{}]", savedJob.getId());
                }
            }
        );

        return mapToResponse(savedJob);
    }

    @Override
    @Transactional
    public int triggerEnrichmentForProject(UUID projectId) {
        projectAccessService.requireCanTriggerAiJob(projectId);
        List<ApiEndpoint> endpoints = apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrue(projectId);
        if (endpoints.isEmpty()) {
            return 0;
        }

        AiSkill aiSkill = aiSkillRepository.findBySkillCode("enrich_api_doc")
                .orElseThrow(() -> new BadRequestException("SkillCode không hợp lệ: enrich_api_doc"));

        int count = 0;
        int skippedCount = 0;
        for (ApiEndpoint endpoint : endpoints) {
            AiJobLog savedJob = createPendingJobIfNotExists(
                    projectId,
                    endpoint.getId(),
                    JobType.DOCUMENT_ENRICHMENT,
                    aiSkill.getId(),
                    "gemini-1.5-flash",
                    false
            );

            if (savedJob == null) {
                skippedCount++;
                continue;
            }

            String path = endpoint.getEndpointPath() != null ? endpoint.getEndpointPath() : "/";
            String method = endpoint.getHttpMethod() != null ? endpoint.getHttpMethod().name().toLowerCase() : "get";
            String opId = endpoint.getOperationId() != null ? endpoint.getOperationId() : (endpoint.getMethodName() != null ? endpoint.getMethodName() : "operation");
            String summary = endpoint.getMethodName() != null ? endpoint.getMethodName() : opId;

            String promptText = String.format("{\n  \"%s\" : {\n    \"%s\" : {\n      \"operationId\" : \"%s\",\n      \"summary\" : \"%s\"\n    }\n  }\n}",
                    path, method, opId, summary);

            AiTaskMessage message = AiTaskMessage.builder()
                    .jobId(savedJob.getId().toString())
                    .promptText(promptText)
                    .skillCode("enrich_api_doc")
                    .projectId(projectId.toString())
                    .apiEndpointId(endpoint.getId().toString())
                    .build();

            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        aiTaskProducer.sendAiTask(message);
                        log.info("Đã đẩy AiTaskMessage vào RabbitMQ cho Job ID: [{}]", savedJob.getId());
                    }
                }
            );

            count++;
        }

        log.info("triggerEnrichmentForProject: queued={}, skipped={}", count, skippedCount);
        return count;
    }

    @Override
    @Transactional
    public AiJobLog createPendingJobIfNotExists(
            UUID projectId,
            UUID apiEndpointId,
            JobType jobType,
            UUID aiSkillId,
            String modelName,
            boolean forceRegenerate) {

        if (!forceRegenerate) {
            boolean hasActive = aiJobLogRepository.existsBySourceProject_IdAndApiEndpoint_IdAndJobTypeAndExecutionStatusIn(
                    projectId, apiEndpointId, jobType, List.of(ExecutionStatus.PENDING, ExecutionStatus.RUNNING));
            if (hasActive) {
                log.debug("Skip duplicate AI job: PENDING/RUNNING already exists for endpoint {}", apiEndpointId);
                return null;
            }

            var latestOpt = aiJobLogRepository.findTopBySourceProject_IdAndApiEndpoint_IdAndJobTypeOrderByStartedAtDesc(
                    projectId, apiEndpointId, jobType);
            if (latestOpt.isPresent()) {
                if (latestOpt.get().getExecutionStatus() == ExecutionStatus.SUCCESS) {
                    log.debug("Skip duplicate AI job: SUCCESS already exists for endpoint {}", apiEndpointId);
                    return null;
                }
            }
        }

        SourceProject projectRef = entityManager.getReference(SourceProject.class, projectId);
        ApiEndpoint endpointRef = entityManager.getReference(ApiEndpoint.class, apiEndpointId);
        AiSkill skillRef = entityManager.getReference(AiSkill.class, aiSkillId);

        AiJobLog jobLog = AiJobLog.builder()
                .executionStatus(ExecutionStatus.PENDING)
                .startedAt(LocalDateTime.now())
                .jobType(jobType)
                .modelName(modelName)
                .sourceProject(projectRef)
                .apiEndpoint(endpointRef)
                .aiSkill(skillRef)
                .build();

        AiJobLog savedJob = aiJobLogRepository.save(jobLog);
        log.info("Đã tạo AiJobLog ID: [{}] trạng thái PENDING cho Endpoint: [{}] (Regenerate: {})",
                savedJob.getId(), apiEndpointId, forceRegenerate);
        return savedJob;
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
            projectAccessService.requireCanTriggerAiJob(request.getProjectId());
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
            log.warn("Job is not in PENDING state (Current: {}). Transitioning to RUNNING anyway due to retry.", jobLog.getExecutionStatus());
        }

        jobLog.setExecutionStatus(ExecutionStatus.RUNNING);
        aiJobLogRepository.save(jobLog);
        log.info("Job {} transitioned from PENDING to RUNNING", id);
    }

    @Override
    @Transactional
    public void updateTokens(UUID id, Integer tokenInput, Integer tokenOutput) {
        AiJobLog jobLog = aiJobLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AiJobLog not found with id: " + id));

        jobLog.setTokenInput(tokenInput);
        jobLog.setTokenOutput(tokenOutput);
        aiJobLogRepository.save(jobLog);
        log.info("Job {} updated with tokens: Input={}, Output={}", id, tokenInput, tokenOutput);
    }

    @Override
    @Transactional
    public void updateAiModelUsed(UUID id, String aiModelUsed) {
        AiJobLog jobLog = aiJobLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AiJobLog not found with id: " + id));

        jobLog.setAiModelUsed(aiModelUsed);
        aiJobLogRepository.save(jobLog);
        log.info("Job {} updated with AI Model Used: {}", id, aiModelUsed);
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
    public void markJobAsSuccess(UUID id, Integer tokenInput, Integer tokenOutput, String modelName) {
        AiJobLog jobLog = aiJobLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AiJobLog not found with id: " + id));

        if (jobLog.getExecutionStatus() != ExecutionStatus.RUNNING) {
            throw new BadRequestException("Job must be RUNNING to succeed");
        }

        jobLog.setExecutionStatus(ExecutionStatus.SUCCESS);
        jobLog.setTokenInput(tokenInput);
        jobLog.setTokenOutput(tokenOutput);
        jobLog.setModelName(modelName);
        jobLog.setCompletedAt(LocalDateTime.now());
        aiJobLogRepository.save(jobLog);
        log.info("Job {} transitioned from RUNNING to SUCCESS with model: {}", id, modelName);
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
        if (jobLog.getSourceProject() != null) {
            projectAccessService.requireCanViewProject(jobLog.getSourceProject().getId());
        } else if (!currentUserService.isAdmin()) {
            throw new ForbiddenException("You do not have permission to perform this action");
        }
        return mapToResponse(jobLog);
    }

    @Override
    @Transactional(readOnly = true)
    public com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobStatisticResponse getJobStatistics() {
        if (!currentUserService.isAdmin()) {
            throw new ForbiddenException("You do not have permission to perform this action");
        }
        long totalJobs = aiJobLogRepository.count();
        long totalSuccessfulJobs = aiJobLogRepository.countByExecutionStatus(ExecutionStatus.SUCCESS);
        long totalFailedJobs = aiJobLogRepository.countByExecutionStatus(ExecutionStatus.FAILED);
        long totalTokenInput = aiJobLogRepository.sumTotalTokenInput();
        long totalTokenOutput = aiJobLogRepository.sumTotalTokenOutput();

        return com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobStatisticResponse.builder()
                .totalJobs(totalJobs)
                .totalSuccessfulJobs(totalSuccessfulJobs)
                .totalFailedJobs(totalFailedJobs)
                .totalTokenInput(totalTokenInput)
                .totalTokenOutput(totalTokenOutput)
                .build();
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