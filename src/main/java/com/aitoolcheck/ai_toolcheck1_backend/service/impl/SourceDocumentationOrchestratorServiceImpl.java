package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobLogResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.apimetadata.res.ApiMetadataParseResultResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.openapi.res.OpenApiGenerateResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceanalysisresult.res.SourceAnalysisResultDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceDocumentationPipelineResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.FileType;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiSkillRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceFileRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiMetadataParserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.OpenApiGeneratorService;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceAnalysisResultService;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceDocumentationOrchestratorService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.aitoolcheck.ai_toolcheck1_backend.service.legacy.LegacyEntrypointCandidateResult;
import com.aitoolcheck.ai_toolcheck1_backend.service.legacy.LegacyEntrypointClassifierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrator service for the "generate documentation from source code" pipeline.
 *
 * <p>Flow: analyzeProject → parseProject (if parserRecommended) →
 * triggerLegacyAiJobs (if aiRecommended) → generateAndSaveOpenApi (if parse-only, no AI).</p>
 *
 * <p>Design constraints: no LLM calls, no HTTP request blocking,
 * no duplicate PENDING/RUNNING jobs, no AI trigger for deleted/inactive/no-content files,
 * no AI for helper/non-entrypoint files (classified by LegacyEntrypointClassifierService).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceDocumentationOrchestratorServiceImpl implements SourceDocumentationOrchestratorService {

    private static final String SKILL_CODE_LEGACY = "legacy_code_reader";
    private static final String PROVIDER_PLAN_LABEL = "GEMINI_PRIMARY_OLLAMA_FALLBACK";

    private static final Set<FileType> EXCLUDED_FILE_TYPES = Set.of(
            FileType.TEST,
            FileType.CONFIG,
            FileType.SECURITY,
            FileType.FILTER,
            FileType.INTERCEPTOR,
            FileType.ENUM,
            FileType.CONSTANT,
            FileType.ANNOTATION
    );

    private final SourceAnalysisResultService sourceAnalysisResultService;
    private final ApiMetadataParserService apiMetadataParserService;
    private final AiJobLogService aiJobLogService;
    private final OpenApiGeneratorService openApiGeneratorService;
    private final ProjectAccessService projectAccessService;
    private final SourceFileRepository sourceFileRepository;
    private final AiSkillRepository aiSkillRepository;
    private final LegacyEntrypointClassifierService classifierService;

    @Override
    @Transactional
    public SourceDocumentationPipelineResponse generateDocsFromSource(UUID projectId) {
        log.info("[DocOrchestrator] START generateDocsFromSource projectId={}", projectId);

        projectAccessService.requireCanGenerateDocs(projectId);

        SourceAnalysisResultDetailResponse analysis = sourceAnalysisResultService.analyzeProject(projectId);
        log.info("[DocOrchestrator] analyzeProject done → parserRecommended={}, aiRecommended={}, sourceStyle={}",
                analysis.getParserRecommended(), analysis.getAiRecommended(), analysis.getSourceStyle());

        boolean parserExecuted = false;
        boolean openApiGenerated = false;
        UUID docVersionId = null;
        List<UUID> aiJobIds = new ArrayList<>();

        // Generate a scan-level UUID so FE can filter jobs from this batch only
        UUID scanBatchId = UUID.randomUUID();
        log.info("[DocOrchestrator] scanBatchId={} for projectId={}", scanBatchId, projectId);

        // Track classifier results for response
        int candidateFileCount = 0;
        int skippedFileCount = 0;
        List<String> skippedReasons = new ArrayList<>();

        if (Boolean.TRUE.equals(analysis.getParserRecommended())) {
            try {
                ApiMetadataParseResultResponse parseResult = apiMetadataParserService.parseProject(projectId);
                parserExecuted = true;
                log.info("[DocOrchestrator] parseProject done → endpoints={}, schemas={}",
                        parseResult.getTotalEndpoints(), parseResult.getTotalSchemas());
            } catch (Exception e) {
                log.warn("[DocOrchestrator] parseProject failed projectId={} — {}", projectId, e.getMessage());
            }
        }

        if (Boolean.TRUE.equals(analysis.getAiRecommended())) {
            // Run classifier BEFORE creating jobs
            TriggerResult triggerResult = triggerLegacyAiJobs(projectId, scanBatchId);
            aiJobIds = triggerResult.jobIds;
            candidateFileCount = triggerResult.candidateCount;
            skippedFileCount = triggerResult.skippedCount;
            skippedReasons = triggerResult.skippedReasons;
            log.info("[DocOrchestrator] Triggered {} AI job(s) for {} candidate file(s), skipped {} helper file(s) — projectId={}",
                    aiJobIds.size(), candidateFileCount, skippedFileCount, projectId);
        }

        if (aiJobIds.isEmpty() && parserExecuted) {
            try {
                OpenApiGenerateResponse openApiResp = openApiGeneratorService.generateAndSaveOpenApi(projectId);
                openApiGenerated = true;
                docVersionId = openApiResp.getApiDocumentVersionId();
                log.info("[DocOrchestrator] OpenAPI generated versionId={}", docVersionId);
            } catch (Exception e) {
                log.warn("[DocOrchestrator] generateAndSaveOpenApi failed projectId={} — {}", projectId, e.getMessage());
            }
        }

        log.info("[DocOrchestrator] END generateDocsFromSource projectId={} | parserExecuted={} | aiJobs={} | openApiGenerated={}",
                projectId, parserExecuted, aiJobIds.size(), openApiGenerated);

        return SourceDocumentationPipelineResponse.builder()
                .projectId(projectId)
                .sourceStyle(analysis.getSourceStyle())
                .parserRecommended(analysis.getParserRecommended())
                .aiRecommended(analysis.getAiRecommended())
                .parserExecuted(parserExecuted)
                .aiJobsTriggered(aiJobIds.size())
                .aiJobIds(aiJobIds)
                .openApiGenerated(openApiGenerated)
                .apiDocumentVersionId(docVersionId)
                .message(buildPipelineMessage(parserExecuted, aiJobIds, openApiGenerated))
                // new fields
                .scanBatchId(scanBatchId)
                .candidateFiles(candidateFileCount)
                .skippedFiles(skippedFileCount)
                .skippedReasons(skippedReasons)
                .providerPlan(Boolean.TRUE.equals(analysis.getAiRecommended()) ? PROVIDER_PLAN_LABEL : null)
                .build();
    }

    // =========================================================================
    // Internal types
    // =========================================================================

    private record TriggerResult(List<UUID> jobIds, int candidateCount, int skippedCount, List<String> skippedReasons) {}

    // =========================================================================
    // Legacy AI job triggering
    // =========================================================================

    /**
     * Runs the entrypoint classifier on all active source files, then creates AI jobs
     * ONLY for classified candidates.  Helper/non-entrypoint files are skipped entirely —
     * they never reach the queue and therefore can never be FAILED.
     *
     * @param projectId  Target project.
     * @param scanBatchId UUID generated for this scan run — persisted on each created AiJobLog.
     */
    private TriggerResult triggerLegacyAiJobs(UUID projectId, UUID scanBatchId) {
        List<SourceFile> activeFiles = findActiveSourceFiles(projectId);
        if (activeFiles.isEmpty()) {
            log.info("[DocOrchestrator] No active source files for projectId={}", projectId);
            return new TriggerResult(List.of(), 0, 0, List.of());
        }

        // Check skill exists before classifying
        boolean skillExists = aiSkillRepository.existsBySkillCode(SKILL_CODE_LEGACY);
        if (!skillExists) {
            log.warn("[DocOrchestrator] AiSkill '{}' not found in DB — skipping AI trigger", SKILL_CODE_LEGACY);
            return new TriggerResult(List.of(), 0, activeFiles.size(), List.of("Skill 'legacy_code_reader' not found in DB"));
        }

        // Run classifier
        List<LegacyEntrypointCandidateResult> classificationResults = classifierService.classifyAll(activeFiles);

        List<UUID> jobIds = new ArrayList<>();
        List<String> skippedReasons = new ArrayList<>();
        int skippedCount = 0;
        int candidateCount = 0;

        for (LegacyEntrypointCandidateResult result : classificationResults) {
            if (!result.isCandidate()) {
                skippedCount++;
                String reason = result.getFileName() + ": " + result.getCandidateType()
                        + " [score=" + result.getScore() + "] — " +
                        (result.getReasons().isEmpty() ? "non-entrypoint" : result.getReasons().get(0));
                skippedReasons.add(reason);
                log.info("[DocOrchestrator] SKIP helper file: {} (type={}, score={})",
                        result.getFilePath(), result.getCandidateType(), result.getScore());
                continue;
            }

            candidateCount++;
            try {
                AiJobLogResponse jobResponse = aiJobLogService.createPendingJobAndTriggerAi(
                        "Analyze this Java source file and extract all HTTP API endpoints.",
                        SKILL_CODE_LEGACY,
                        projectId,
                        result.getSourceFileId(),
                        null,
                        scanBatchId   // persist batch UUID on the job record
                );
                jobIds.add(jobResponse.getId());
                log.info("[DocOrchestrator] Enqueued candidate file={} path={} type={} → jobId={}",
                        result.getSourceFileId(), result.getFilePath(), result.getCandidateType(), jobResponse.getId());
            } catch (Exception e) {
                log.warn("[DocOrchestrator] Failed to enqueue file={} ({}) — {}", result.getSourceFileId(), result.getFileName(), e.getMessage());
            }
        }

        log.info("[DocOrchestrator] Classifier result: total={}, candidates={}, skipped={} for projectId={}",
                activeFiles.size(), candidateCount, skippedCount, projectId);

        return new TriggerResult(jobIds, candidateCount, skippedCount, skippedReasons);
    }

    /**
     * Load active, non-deleted source files that have content and are not in EXCLUDED file types.
     */
    private List<SourceFile> findActiveSourceFiles(UUID projectId) {
        List<SourceFile> allActive = sourceFileRepository.findBySourceProjectIdAndActiveFlagTrue(projectId);
        return allActive.stream()
                .filter(f -> f.getSourceContent() != null && !f.getSourceContent().isBlank())
                .filter(f -> !Boolean.TRUE.equals(f.getDeletedFlag()))
                .filter(f -> f.getFileType() == null || !EXCLUDED_FILE_TYPES.contains(f.getFileType()))
                .toList();
    }

    private String buildPipelineMessage(boolean parserExecuted, List<UUID> aiJobIds, boolean openApiGenerated) {
        StringBuilder sb = new StringBuilder();

        if (parserExecuted) {
            sb.append("JavaParser đã parse metadata. ");
        }

        if (!aiJobIds.isEmpty()) {
            sb.append(aiJobIds.size())
              .append(" AI job(s) đã được tạo và enqueue (legacy_code_reader). ")
              .append("Sau khi jobs hoàn tất, gọi POST /generate-openapi để tạo tài liệu OpenAPI.");
        } else if (parserExecuted && openApiGenerated) {
            sb.append("OpenAPI đã được generate và lưu thành công.");
        } else if (parserExecuted) {
            sb.append("Parse metadata thành công nhưng generate OpenAPI thất bại — vui lòng gọi lại generate-openapi.");
        } else {
            sb.append("Không có file candidate nào phù hợp. Kiểm tra lại source content hoặc chạy lại analyze-source.");
        }

        return sb.toString().trim();
    }
}
