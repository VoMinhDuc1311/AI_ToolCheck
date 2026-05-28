package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.res.ApiEndpointDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apiendpoint.res.ApiEndpointResponse;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiEndpointService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.stream.Stream;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestCase;
import com.aitoolcheck.ai_toolcheck1_backend.model.TestRunItem;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApiEndpointServiceImpl implements ApiEndpointService {

    private final ApiEndpointRepository apiEndpointRepository;
    private final SourceProjectRepository sourceProjectRepository;
    private final ProjectAccessService projectAccessService;

    @Override
    @Transactional(readOnly = true)
    public List<ApiEndpointResponse> getByProjectId(UUID projectId) {
        projectAccessService.requireCanViewProject(projectId);

        return apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ApiEndpointDetailResponse getById(UUID id) {
        ApiEndpoint apiEndpoint = apiEndpointRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("API endpoint not found with id: " + id));
        projectAccessService.requireCanViewProject(apiEndpoint.getSourceProject().getId());

        return mapToDetailResponse(apiEndpoint);
    }

    @Override
    @Transactional
    public void enrichEndpointData(UUID id, String summary, String description, String reqJson, String resJson, String openapiFragmentJson, UUID jobId) {
        log.info("[ApiEndpoint] Cập nhật dữ liệu do AI làm giàu cho Endpoint ID: {}", id);

        ApiEndpoint endpoint = apiEndpointRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("API endpoint not found with id: " + id));
        projectAccessService.requireCanTriggerAiJob(endpoint.getSourceProject().getId());

        applyEnrichmentFields(endpoint, summary, description, reqJson, resJson, openapiFragmentJson, jobId);
        apiEndpointRepository.save(endpoint);
        log.debug("[ApiEndpoint] Cập nhật thành công (HTTP path) cho Endpoint ID: {}", id);
    }

    /**
     * Async-safe internal persistence method for RabbitMQ workers.
     * Does NOT call CurrentUserService or ProjectAccessService.
     * Permission was already verified at HTTP trigger time.
     */
    @Override
    @Transactional
    public void enrichEndpointDataFromAiJob(UUID id, String summary, String description, String reqJson, String resJson, String openapiFragmentJson, UUID jobId) {
        log.info("[ApiEndpoint][AsyncJob] Cập nhật dữ liệu do AI làm giàu cho Endpoint ID: {}", id);

        ApiEndpoint endpoint = apiEndpointRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("API endpoint not found with id: " + id));

        applyEnrichmentFields(endpoint, summary, description, reqJson, resJson, openapiFragmentJson, jobId);
        apiEndpointRepository.save(endpoint);
        log.debug("[ApiEndpoint][AsyncJob] Cập nhật thành công cho Endpoint ID: {}", id);
    }

    /** Shared field-update logic — no auth, no side effects. */
    private void applyEnrichmentFields(ApiEndpoint endpoint, String summary, String description,
            String reqJson, String resJson, String openapiFragmentJson, UUID jobId) {
        endpoint.setAiSummary(summary);
        endpoint.setAiDescription(description);
        endpoint.setExampleRequestJson(reqJson);
        endpoint.setExampleResponseJson(resJson);
        endpoint.setOpenapiFragmentJson(openapiFragmentJson);
        endpoint.setAiEnrichedAt(LocalDateTime.now());
        endpoint.setLastAiJobLogId(jobId);
        endpoint.setAiEnrichedFlag(true);
    }

    private ApiEndpointResponse mapToResponse(ApiEndpoint apiEndpoint) {
        return ApiEndpointResponse.builder()
                .id(apiEndpoint.getId())
                .projectId(apiEndpoint.getSourceProject().getId())
                .sourceFileId(apiEndpoint.getSourceFile() == null ? null : apiEndpoint.getSourceFile().getId())
                .sourceUploadVersionId(apiEndpoint.getSourceUploadVersion() == null ? null : apiEndpoint.getSourceUploadVersion().getId())
                .controllerName(apiEndpoint.getControllerName())
                .methodName(apiEndpoint.getMethodName())
                .httpMethod(apiEndpoint.getHttpMethod())
                .endpointPath(apiEndpoint.getEndpointPath())
                .stableKey(apiEndpoint.getStableKey())
                .description(apiEndpoint.getDescription())
                .operationId(apiEndpoint.getOperationId())
                .tagName(apiEndpoint.getTagName())
                .authRequired(apiEndpoint.getAuthRequired())
                .deprecatedFlag(apiEndpoint.getDeprecatedFlag())
                .activeFlag(apiEndpoint.getActiveFlag())
                .staleFlag(apiEndpoint.getStaleFlag())
                .aiEnrichedFlag(apiEndpoint.getAiEnrichedFlag())
                .aiSummary(apiEndpoint.getAiSummary())
                .aiDescription(apiEndpoint.getAiDescription())
                .aiEnrichedAt(apiEndpoint.getAiEnrichedAt())
                .build();
    }

    private ApiEndpointDetailResponse mapToDetailResponse(ApiEndpoint apiEndpoint) {
        // Map sourceFile
        ApiEndpointDetailResponse.SourceFileInfo sourceFileInfo = null;
        SourceFile sf = apiEndpoint.getSourceFile();
        if (sf != null) {
            sourceFileInfo = ApiEndpointDetailResponse.SourceFileInfo.builder()
                    .id(sf.getId())
                    .fileName(sf.getFileName())
                    .filePath(sf.getFilePath())
                    .packageName(sf.getPackageName())
                    .className(sf.getClassName())
                    .build();
        }

        // Map latestAiJob
        ApiEndpointDetailResponse.LatestAiJobInfo latestJobInfo = null;
        if (apiEndpoint.getAiJobLogs() != null && !apiEndpoint.getAiJobLogs().isEmpty()) {
            AiJobLog latestJob = apiEndpoint.getAiJobLogs().stream()
                    .filter(job -> job.getStartedAt() != null)
                    .max(Comparator.comparing(AiJobLog::getStartedAt))
                    .orElse(null);
            if (latestJob != null) {
                latestJobInfo = ApiEndpointDetailResponse.LatestAiJobInfo.builder()
                        .id(latestJob.getId())
                        .jobType(latestJob.getJobType() != null ? latestJob.getJobType().name() : null)
                        .executionStatus(latestJob.getExecutionStatus() != null ? latestJob.getExecutionStatus().name() : null)
                        .errorMessage(latestJob.getErrorMessage())
                        .startedAt(latestJob.getStartedAt())
                        .completedAt(latestJob.getCompletedAt())
                        .build();
            }
        }

        // Map stats
        List<TestCase> activeTestCases = apiEndpoint.getTestCases() != null ?
                apiEndpoint.getTestCases().stream()
                        .filter(tc -> tc.getDeletedFlag() == null || !tc.getDeletedFlag())
                        .toList() : List.of();

        int testCaseCount = activeTestCases.size();
        int assertionCount = 0;
        for (TestCase tc : activeTestCases) {
            if (tc.getTestCaseAssertions() != null) {
                assertionCount += tc.getTestCaseAssertions().size();
            }
        }

        List<TestRunItem> allItems = activeTestCases.stream()
                .flatMap(tc -> tc.getTestRunItems() != null ? tc.getTestRunItems().stream() : Stream.empty())
                .toList();

        TestRunItem latestItem = allItems.stream()
                .filter(item -> item.getCreatedAt() != null)
                .max(Comparator.comparing(TestRunItem::getCreatedAt))
                .orElse(null);

        String latestRunStatus = null;
        if (latestItem != null) {
            if (latestItem.getTestResult() != null && latestItem.getTestResult().getResultStatus() != null) {
                latestRunStatus = latestItem.getTestResult().getResultStatus().name();
            } else {
                latestRunStatus = latestItem.getItemStatus() != null ? latestItem.getItemStatus().name() : null;
            }
        }

        long failureCount = allItems.stream()
                .filter(item -> {
                    if (item.getItemStatus() == ExecutionStatus.FAILED) {
                        return true;
                    }
                    if (item.getTestResult() != null) {
                        ResultStatus rs = item.getTestResult().getResultStatus();
                        return rs == ResultStatus.FAIL || rs == ResultStatus.ERROR;
                    }
                    return false;
                })
                .count();

        ApiEndpointDetailResponse.EndpointStats stats = ApiEndpointDetailResponse.EndpointStats.builder()
                .testCaseCount(testCaseCount)
                .assertionCount(assertionCount)
                .latestRunStatus(latestRunStatus)
                .failureCount((int) failureCount)
                .build();

        return ApiEndpointDetailResponse.builder()
                .id(apiEndpoint.getId())
                .projectId(apiEndpoint.getSourceProject().getId())
                .sourceFileId(apiEndpoint.getSourceFile() == null ? null : apiEndpoint.getSourceFile().getId())
                .sourceUploadVersionId(apiEndpoint.getSourceUploadVersion() == null ? null : apiEndpoint.getSourceUploadVersion().getId())
                .controllerName(apiEndpoint.getControllerName())
                .methodName(apiEndpoint.getMethodName())
                .httpMethod(apiEndpoint.getHttpMethod())
                .endpointPath(apiEndpoint.getEndpointPath())
                .stableKey(apiEndpoint.getStableKey())
                .description(apiEndpoint.getDescription())
                .operationId(apiEndpoint.getOperationId())
                .tagName(apiEndpoint.getTagName())
                .authRequired(apiEndpoint.getAuthRequired())
                .deprecatedFlag(apiEndpoint.getDeprecatedFlag())
                .activeFlag(apiEndpoint.getActiveFlag())
                .staleFlag(apiEndpoint.getStaleFlag())
                .createdAt(apiEndpoint.getCreatedAt())
                .updatedAt(apiEndpoint.getUpdatedAt())
                .aiEnrichedFlag(apiEndpoint.getAiEnrichedFlag())
                .aiSummary(apiEndpoint.getAiSummary())
                .aiDescription(apiEndpoint.getAiDescription())
                .exampleRequestJson(apiEndpoint.getExampleRequestJson())
                .exampleResponseJson(apiEndpoint.getExampleResponseJson())
                .openapiFragmentJson(apiEndpoint.getOpenapiFragmentJson())
                .aiEnrichedAt(apiEndpoint.getAiEnrichedAt())
                .lastAiJobLogId(apiEndpoint.getLastAiJobLogId())
                .sourceFile(sourceFileInfo)
                .latestAiJob(latestJobInfo)
                .stats(stats)
                .build();
    }
}
