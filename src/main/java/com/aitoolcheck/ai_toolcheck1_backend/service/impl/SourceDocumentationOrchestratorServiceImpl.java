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
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiMetadataCleanupService;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiMetadataParserService;
import com.aitoolcheck.ai_toolcheck1_backend.service.OpenApiGeneratorService;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceAnalysisResultService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrator service cho pipeline "sinh tài liệu từ source code".
 *
 * <p>Flow: analyzeProject → parseProject (nếu parserRecommended) →
 * triggerLegacyAiJobs (nếu aiRecommended) → generateAndSaveOpenApi (nếu chỉ parse, không AI).</p>
 *
 * <p>Ràng buộc thiết kế: không gọi LLM trực tiếp, không block HTTP request,
 * không tạo duplicate job PENDING/RUNNING, không trigger AI cho file xóa/inactive/không content.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceDocumentationOrchestratorServiceImpl {

    private static final String SKILL_CODE_LEGACY = "legacy_code_reader";

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
    private final ApiMetadataCleanupService apiMetadataCleanupService;

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
            aiJobIds = triggerLegacyAiJobs(projectId);
            log.info("[DocOrchestrator] Triggered {} AI job(s) projectId={}", aiJobIds.size(), projectId);
        }

        if (aiJobIds.isEmpty() && parserExecuted) {
            try {
                apiMetadataCleanupService.cleanupProjectApiMetadata(projectId);
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
                .build();
    }

    private List<UUID> triggerLegacyAiJobs(UUID projectId) {
        List<SourceFile> candidates = findLegacyCandidateFiles(projectId);
        if (candidates.isEmpty()) {
            log.info("[DocOrchestrator] No legacy candidate files for projectId={}", projectId);
            return List.of();
        }

        boolean skillExists = aiSkillRepository.existsBySkillCode(SKILL_CODE_LEGACY);
        if (!skillExists) {
            log.warn("[DocOrchestrator] AiSkill '{}' not found in DB — skipping AI trigger", SKILL_CODE_LEGACY);
            return List.of();
        }

        log.info("[DocOrchestrator] {} legacy candidate file(s) found", candidates.size());
        List<UUID> jobIds = new ArrayList<>();

        for (SourceFile file : candidates) {
            try {
                AiJobLogResponse jobResponse = aiJobLogService.createPendingJobAndTriggerAi(
                        "Analyze this Java source file and extract all HTTP API endpoints.",
                        SKILL_CODE_LEGACY,
                        projectId,
                        file.getId(),
                        null
                );
                jobIds.add(jobResponse.getId());
                log.info("[DocOrchestrator] Enqueued file={} path={} → jobId={}",
                        file.getId(), file.getFilePath(), jobResponse.getId());
            } catch (Exception e) {
                log.warn("[DocOrchestrator] Skipping file={} ({}) — {}", file.getId(), file.getFileName(), e.getMessage());
            }
        }

        return jobIds;
    }

    /**
     * Lọc file hợp lệ để gửi AI: active, có content, không phải loại không liên quan,
     * và thuộc nhóm chưa parse được / unknown / controller / service.
     */
    private List<SourceFile> findLegacyCandidateFiles(UUID projectId) {
        List<SourceFile> activeFiles = sourceFileRepository.findBySourceProjectIdAndActiveFlagTrue(projectId);

        List<SourceFile> candidates = activeFiles.stream()
                .filter(f -> f.getSourceContent() != null && !f.getSourceContent().isBlank())
                .filter(f -> !Boolean.TRUE.equals(f.getDeletedFlag()))
                .filter(f -> f.getFileType() == null || !EXCLUDED_FILE_TYPES.contains(f.getFileType()))
                .filter(this::isLegacyCandidate)
                .toList();

        log.debug("[DocOrchestrator] findLegacyCandidateFiles: active={}, candidates={}",
                activeFiles.size(), candidates.size());
        return candidates;
    }

    private boolean isLegacyCandidate(SourceFile file) {
        FileType ft = file.getFileType();

        if (Boolean.FALSE.equals(file.getParsedFlag()) || file.getParseError() != null) {
            return true;
        }
        if (ft == null || ft == FileType.UNKNOWN) {
            return true;
        }
        return ft == FileType.CONTROLLER || ft == FileType.SERVICE
                || ft == FileType.SERVICE_IMPL || ft == FileType.APPLICATION;
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
