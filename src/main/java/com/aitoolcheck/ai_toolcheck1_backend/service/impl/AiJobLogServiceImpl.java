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
    private final com.aitoolcheck.ai_toolcheck1_backend.config.properties.GeminiProperties geminiProperties;

    @Override
    @Transactional
    public AiJobLogResponse createPendingJobAndTriggerAi(String promptText, String skillCode,
                                                   UUID projectId, UUID sourceFileId, UUID apiEndpointId) {
        return createPendingJobAndTriggerAi(promptText, skillCode, projectId, sourceFileId, apiEndpointId, null);
    }

    @Override
    @Transactional
    public AiJobLogResponse createPendingJobAndTriggerAi(String promptText, String skillCode,
                                                   UUID projectId, UUID sourceFileId, UUID apiEndpointId, UUID scanBatchId) {
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
                .modelName(geminiProperties.getModel())
                .sourceProject(projectRef)
                .aiSkill(aiSkill)
                .scanBatchId(scanBatchId)
                .build();
                
        if (apiEndpointId != null) {
            jobLog.setApiEndpoint(entityManager.getReference(ApiEndpoint.class, apiEndpointId));
        }

        AiJobLog savedJob = aiJobLogRepository.save(jobLog);
        log.info("Đã tạo AiJobLog ID: [{}] trạng thái PENDING cho Project: [{}] với model: [{}]",
                 savedJob.getId(), projectId, geminiProperties.getModel());

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
                    log.info("Đã đẩy AiTaskMessage vào RabbitMQ cho Job ID: [{}], sourceFileId: [{}]", savedJob.getId(), message.getSourceFileId());
                }
            }
        );

        return mapToResponse(savedJob);
    }

    @Override
    @Transactional
    public int triggerEnrichmentForProject(UUID projectId) {
        log.info("[EnrichTrigger] ▶ Bắt đầu trigger enrich endpoints cho Project ID: {}", projectId);
        projectAccessService.requireCanTriggerAiJob(projectId);

        // Use stale-filtered query: only activeFlag=true AND staleFlag=false endpoints
        List<ApiEndpoint> endpoints = apiEndpointRepository
                .findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId);

        log.info("[EnrichTrigger] Project: {} → Tổng endpoint active+non-stale tìm được: {}",
                projectId, endpoints.size());

        if (endpoints.isEmpty()) {
            log.info("[EnrichTrigger] Không có endpoint hợp lệ để enrich cho project: {}", projectId);
            return 0;
        }

        AiSkill aiSkill = aiSkillRepository.findBySkillCode("enrich_api_doc")
                .orElseThrow(() -> new BadRequestException("SkillCode không hợp lệ: enrich_api_doc"));

        int count = 0;
        int skippedPendingRunning = 0;
        int skippedAlreadySuccess = 0;

        for (ApiEndpoint endpoint : endpoints) {
            UUID endpointId = endpoint.getId();
            String endpointDesc = String.format("[%s %s id=%s]",
                    endpoint.getHttpMethod() != null ? endpoint.getHttpMethod().name() : "?",
                    endpoint.getEndpointPath() != null ? endpoint.getEndpointPath() : "/",
                    endpointId);

            // Pre-check PENDING/RUNNING to emit accurate skip reason in log
            boolean hasPendingRunning = aiJobLogRepository
                    .existsBySourceProject_IdAndApiEndpoint_IdAndJobTypeAndExecutionStatusIn(
                            projectId, endpointId, JobType.DOCUMENT_ENRICHMENT,
                            List.of(ExecutionStatus.PENDING, ExecutionStatus.RUNNING));
            if (hasPendingRunning) {
                log.info("[EnrichTrigger] SKIP {} — Đã có job PENDING/RUNNING", endpointDesc);
                skippedPendingRunning++;
                continue;
            }

            // Pre-check SUCCESS
            var latestOpt = aiJobLogRepository
                    .findTopBySourceProject_IdAndApiEndpoint_IdAndJobTypeOrderByStartedAtDesc(
                            projectId, endpointId, JobType.DOCUMENT_ENRICHMENT);
            if (latestOpt.isPresent() && latestOpt.get().getExecutionStatus() == ExecutionStatus.SUCCESS) {
                log.info("[EnrichTrigger] SKIP {} — Job SUCCESS đã tồn tại (jobId: {}). aiEnrichedFlag={}",
                        endpointDesc, latestOpt.get().getId(), endpoint.getAiEnrichedFlag());
                skippedAlreadySuccess++;
                continue;
            }

            // Create PENDING job
            AiJobLog savedJob = createPendingJobIfNotExists(
                    projectId,
                    endpointId,
                    JobType.DOCUMENT_ENRICHMENT,
                    aiSkill.getId(),
                    geminiProperties.getModel(),
                    false
            );

            if (savedJob == null) {
                // Edge case: race condition — another thread created the job between pre-check and now
                log.warn("[EnrichTrigger] SKIP {} — createPendingJobIfNotExists trả null (race condition)",
                        endpointDesc);
                skippedPendingRunning++;
                continue;
            }

            String path = endpoint.getEndpointPath() != null ? endpoint.getEndpointPath() : "/";
            String method = endpoint.getHttpMethod() != null ? endpoint.getHttpMethod().name().toLowerCase() : "get";
            String opId = endpoint.getOperationId() != null ? endpoint.getOperationId()
                    : (endpoint.getMethodName() != null ? endpoint.getMethodName() : "operation");
            String summary = endpoint.getMethodName() != null ? endpoint.getMethodName() : opId;

            String promptText = String.format(
                    "{\n  \"%s\" : {\n    \"%s\" : {\n      \"operationId\" : \"%s\",\n      \"summary\" : \"%s\"\n    }\n  }\n}",
                    path, method, opId, summary);

            AiTaskMessage message = AiTaskMessage.builder()
                    .jobId(savedJob.getId().toString())
                    .promptText(promptText)
                    .skillCode("enrich_api_doc")
                    .projectId(projectId.toString())
                    .apiEndpointId(endpointId.toString())
                    .build();

            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        aiTaskProducer.sendAiTask(message);
                        log.info("[EnrichTrigger] ✓ RabbitMQ message đã gửi cho Job ID: [{}], Endpoint: {}",
                                savedJob.getId(), endpointDesc);
                    }
                }
            );

            log.info("[EnrichTrigger] ENQUEUED {} → Job ID: {}", endpointDesc, savedJob.getId());
            count++;
        }

        log.info("[EnrichTrigger] ◀ Kết thúc. Project: {} | Total: {} | Enqueued: {} | Skip(PENDING/RUNNING): {} | Skip(SUCCESS): {}",
                projectId, endpoints.size(), count, skippedPendingRunning, skippedAlreadySuccess);
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
        markJobAsSuccess(id, tokenInput, tokenOutput, modelName, null);
    }

    @Override
    @Transactional
    public void markJobAsSuccess(UUID id, Integer tokenInput, Integer tokenOutput, String modelName, String message) {
        AiJobLog jobLog = aiJobLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AiJobLog not found with id: " + id));

        if (jobLog.getExecutionStatus() != ExecutionStatus.RUNNING) {
            throw new BadRequestException("Job must be RUNNING to succeed");
        }

        jobLog.setExecutionStatus(ExecutionStatus.SUCCESS);
        jobLog.setTokenInput(tokenInput);
        jobLog.setTokenOutput(tokenOutput);
        jobLog.setModelName(modelName);
        jobLog.setErrorMessage(message);
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

    @Override
    @Transactional(readOnly = true)
    public List<AiJobLogResponse> getJobLogs(UUID projectId) {
        if (projectId != null) {
            projectAccessService.requireCanViewProject(projectId);
            return aiJobLogRepository.findBySourceProject_IdOrderByStartedAtDesc(projectId)
                    .stream()
                    .map(this::mapToResponse)
                    .collect(java.util.stream.Collectors.toList());
        } else {
            if (currentUserService.isAdmin()) {
                return aiJobLogRepository.findAllByOrderByStartedAtDesc()
                        .stream()
                        .map(this::mapToResponse)
                        .collect(java.util.stream.Collectors.toList());
            } else {
                UUID currentUserId = currentUserService.getCurrentUser().getId();
                List<UUID> accessibleProjectIds = sourceProjectRepository.findActiveNonArchivedOwnedOrMemberProjectIds(currentUserId);
                if (accessibleProjectIds.isEmpty()) {
                    return List.of();
                }
                return aiJobLogRepository.findBySourceProject_IdInOrderByStartedAtDesc(accessibleProjectIds)
                        .stream()
                        .map(this::mapToResponse)
                        .collect(java.util.stream.Collectors.toList());
            }
        }
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
                .startedAt(jobLog.getStartedAt())
                .completedAt(jobLog.getCompletedAt())
                .scanBatchId(jobLog.getScanBatchId())
                .errorMessage(jobLog.getErrorMessage())
                .build();
    }
}
