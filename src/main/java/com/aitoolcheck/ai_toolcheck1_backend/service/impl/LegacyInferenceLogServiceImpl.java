package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.enums.LogStatus;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiPersistenceException;
import com.aitoolcheck.ai_toolcheck1_backend.model.LegacyInferenceLog;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.LegacyInferenceLogRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceFileRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.LegacyInferenceLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Triển khai Audit Trail Service theo nguyên tắc SRP.
 *
 * <h3>Phân tách trách nhiệm</h3>
 * <ul>
 *   <li>{@link #createLog}          – Entry point, điều phối và xử lý lỗi.</li>
 *   <li>{@link #buildInferenceLog}  – Chỉ lo khởi tạo Entity, KHÔNG lưu DB.</li>
 *   <li>{@link #scaleConfidence}    – Chỉ lo convert số, KHÔNG biết Entity là gì.</li>
 * </ul>
 *
 * <h3>getReferenceById vs findById</h3>
 * <p>
 * Dùng {@code getReferenceById()} thay vì {@code findById()} để tạo Proxy Reference
 * — tránh tốn thêm SELECT query khi chỉ cần set FK relationship.
 * JPA sẽ chỉ thực sự SELECT nếu code đọc field khác ngoài ID.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegacyInferenceLogServiceImpl implements LegacyInferenceLogService {

    private final LegacyInferenceLogRepository legacyInferenceLogRepository;
    private final SourceProjectRepository sourceProjectRepository;
    private final SourceFileRepository sourceFileRepository;
    private final ApiEndpointRepository apiEndpointRepository;

    /**
     * Tạo và persist một Audit Log entry cho một lần AI inference.
     * <p>
     * {@code @Transactional} đặt ở đây — transaction chỉ mở khi cần INSERT vào DB
     * (dưới 100ms), không bao giờ bao phủ quá trình gọi Gemini API.
     * </p>
     */
    @Override
    @Transactional
    public LegacyInferenceLog createLog(UUID projectId,
                                        UUID sourceFileId,
                                        UUID apiEndpointId,
                                        String rawResponse,
                                        String cleanJson,
                                        Double confidenceScore,
                                        LogStatus status,
                                        String errorType) {
        log.debug("[Audit-Start] Chuẩn bị ghi Audit Log — ProjectId: {}, Status: {}",
                  projectId, status);
        try {
            // SRP: buildInferenceLog chỉ lo khởi tạo, createLog lo lưu
            LegacyInferenceLog entity = buildInferenceLog(
                    projectId, sourceFileId, apiEndpointId,
                    rawResponse, cleanJson, confidenceScore, status, errorType
            );

            LegacyInferenceLog saved = legacyInferenceLogRepository.save(entity);
            log.info("[Audit-Success] Log đã lưu — ID: {}, Status: {}", saved.getId(), status);
            return saved;

        } catch (Exception e) {
            log.error("[Audit-Failed] Không thể lưu Audit Log cho Project: {}. Lý do: {}",
                      projectId, e.getMessage());
            throw new AiPersistenceException("Không thể lưu AI Audit Trail cho Project: " + projectId, e);
        }
    }

    // ─── Private: SRP Methods ─────────────────────────────────────────────────

    /**
     * Chịu trách nhiệm duy nhất: khởi tạo {@link LegacyInferenceLog} và map các Proxy Reference.
     * Không thực hiện bất kỳ thao tác lưu DB nào.
     */
    private LegacyInferenceLog buildInferenceLog(UUID projectId,
                                                  UUID sourceFileId,
                                                  UUID apiEndpointId,
                                                  String rawResponse,
                                                  String cleanJson,
                                                  Double confidenceScore,
                                                  LogStatus status,
                                                  String errorType) {
        return LegacyInferenceLog.builder()
                // getReferenceById() → Proxy Reference, không tốn thêm SELECT query
                .sourceProject(sourceProjectRepository.getReferenceById(projectId))
                .sourceFile(sourceFileId != null
                        ? sourceFileRepository.getReferenceById(sourceFileId) : null)
                .apiEndpoint(apiEndpointId != null
                        ? apiEndpointRepository.getReferenceById(apiEndpointId) : null)
                .rawResponse(rawResponse)
                .inferredMetadataJson(cleanJson)
                .confidenceScore(scaleConfidence(confidenceScore))
                .status(status)
                .errorType(errorType)
                .build();
    }

    /**
     * Chuyển đổi điểm confidence từ dạng thập phân (0.0–1.0) sang phần trăm nguyên (0–100).
     * Fallback về 80 nếu AI không cung cấp.
     */
    private Integer scaleConfidence(Double rawScore) {
        if (rawScore == null) return 80;
        return (int) (rawScore * 100);
    }
}
