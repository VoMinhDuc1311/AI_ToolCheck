package com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiInferenceResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.enums.LogStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ParamIn;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiParameter;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceFileRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.LegacyInferenceLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service chuyên trách lưu kết quả AI vào Database (Task 5).
 *
 * <h3>3 lỗ hổng đã vá (Task 5 Review)</h3>
 * <ol>
 *   <li><b>Lỗ hổng 1 — SourceFile FK null:</b> Đã set {@code endpoint.setSourceFile(sourceFileRef)}
 *       → Dev A có thể query "API của file này" bình thường.</li>
 *   <li><b>Lỗ hổng 2 — authRequired &amp; description bị bỏ:</b> Đã map từ DTO vào Entity.</li>
 *   <li><b>Tối ưu N+1:</b> {@code SourceFile} Proxy Reference được lấy 1 lần ngoài vòng lặp,
 *       không lấy lại mỗi iteration.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTaskPersistenceService {

    private final ApiEndpointRepository apiEndpointRepository;
    private final SourceFileRepository sourceFileRepository;       // ✅ Thêm để lấy SourceFile Reference
    private final LegacyInferenceLogService legacyInferenceLogService;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Lưu toàn bộ kết quả AI inference vào Database trong một Transaction.
     * Transaction mở tại đây — sau khi Gemini đã trả về và Parser đã xong.
     * Insert DB diễn ra cực nhanh ({@literal <}100ms) → không lo cạn Connection Pool.
     *
     * @param project      SourceProject gắn với job đang xử lý.
     * @param sourceFileId UUID của SourceFile (nullable).
     * @param rawResponse  Chuỗi thô từ Gemini — lưu vào Audit Log để debug.
     * @param cleanJson    JSON sạch (output Task 1).
     * @param dto          DTO đã được validate bởi AiJsonParserService (Task 2).
     */
    @Transactional
    public void persistLegacyInference(SourceProject project,
                                       UUID sourceFileId,
                                       String rawResponse,
                                       String cleanJson,
                                       AiInferenceResultDto dto) {
        log.info("[Persistence] Bắt đầu lưu DB cho project: {}", project.getId());

        if (dto.getEndpoints().isEmpty()) {
            log.info("[Persistence] AI không tìm thấy endpoint — ghi Audit Log SUCCESS rỗng.");
            legacyInferenceLogService.createLog(
                    project.getId(), sourceFileId, null,
                    rawResponse, cleanJson, null,
                    LogStatus.SUCCESS, null
            );
            return;
        }

        // ✅ Tối ưu: Lấy SourceFile Proxy Reference 1 lần ngoài vòng lặp
        //    getReferenceById() không tốn SELECT — chỉ tạo Proxy với ID
        SourceFile sourceFileRef = (sourceFileId != null)
                ? sourceFileRepository.getReferenceById(sourceFileId)
                : null;

        saveApiEndpointsWithAudit(project, sourceFileRef, sourceFileId, rawResponse, cleanJson, dto);

        log.info("[Persistence] Hoàn thành — {} endpoint(s) đã lưu.", dto.getEndpoints().size());
    }

    // =========================================================================
    // Private Pipeline
    // =========================================================================

    /**
     * Lưu từng ApiEndpoint + ApiParameter, sau đó ghi Audit Log SUCCESS per endpoint.
     */
    private void saveApiEndpointsWithAudit(SourceProject project,
                                            SourceFile sourceFileRef,
                                            UUID sourceFileId,
                                            String rawResponse,
                                            String cleanJson,
                                            AiInferenceResultDto dto) {
        for (AiInferenceResultDto.EndpointDto epDto : dto.getEndpoints()) {
            // Bước 1: Build + lưu ApiEndpoint (bao gồm các parameter qua Cascade)
            ApiEndpoint savedEndpoint = saveApiEndpoint(project, sourceFileRef, epDto);

            // Bước 2: Ghi Audit Log SUCCESS — nối với endpoint vừa lưu
            legacyInferenceLogService.createLog(
                    project.getId(),
                    sourceFileId,
                    savedEndpoint.getId(),   // FK tới endpoint vừa lưu
                    rawResponse,             // Raw text từ Gemini — bằng chứng debug
                    cleanJson,               // JSON đã làm sạch
                    epDto.getConfidence(),   // Confidence của endpoint này
                    LogStatus.SUCCESS,
                    null
            );

            log.debug("[Persistence] Saved endpoint + Audit Log: {} {}",
                      epDto.getHttpMethod(), epDto.getPath());
        }
    }

    private ApiEndpoint saveApiEndpoint(SourceProject project,
                                        SourceFile sourceFileRef,
                                        AiInferenceResultDto.EndpointDto epDto) {
        ApiEndpoint endpoint = buildApiEndpoint(project, sourceFileRef, epDto);
        List<ApiParameter> parameters = buildApiParameters(endpoint, epDto);
        endpoint.setApiParameters(parameters);
        return apiEndpointRepository.save(endpoint);
    }

    /**
     * Build ApiEndpoint entity từ DTO.
     *
     * <ul>
     *   <li>✅ LỖ HỔNG 1 đã vá: set {@code sourceFile} → FK không còn NULL.</li>
     *   <li>✅ LỖ HỔNG 2 đã vá: set {@code authRequired} và {@code description}.</li>
     * </ul>
     */
    private ApiEndpoint buildApiEndpoint(SourceProject project,
                                         SourceFile sourceFileRef,
                                         AiInferenceResultDto.EndpointDto epDto) {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setSourceProject(project);

        // ✅ Lỗ hổng 1: Liên kết SourceFile để Dev A query "API của file X"
        endpoint.setSourceFile(sourceFileRef);

        endpoint.setEndpointPath(epDto.getPath());
        endpoint.setHttpMethod(resolveHttpMethod(epDto.getHttpMethod()));

        // ✅ Lỗ hổng 2: Map description và authRequired từ DTO
        endpoint.setDescription(epDto.getDescription());
        endpoint.setAuthRequired(epDto.getAuthRequired() != null ? epDto.getAuthRequired() : false);

        endpoint.setCreatedAt(LocalDateTime.now());

        if (epDto.getSource() != null) {
            endpoint.setControllerName(epDto.getSource().getClassName());
            endpoint.setMethodName(epDto.getSource().getMethodName());
        }
        return endpoint;
    }

    private List<ApiParameter> buildApiParameters(ApiEndpoint endpoint,
                                                   AiInferenceResultDto.EndpointDto epDto) {
        List<ApiParameter> parameters = new ArrayList<>();
        // epDto.getParameters() đã có default emptyList() từ Task 2 — không cần null check
        for (AiInferenceResultDto.ParameterDto paramDto : epDto.getParameters()) {
            ApiParameter param = new ApiParameter();
            param.setApiEndpoint(endpoint);
            param.setParamName(paramDto.getName());
            param.setDataType(paramDto.getType() != null ? paramDto.getType() : "String");
            param.setRequiredFlag(Boolean.TRUE.equals(paramDto.getRequired()));
            param.setExampleValue(paramDto.getExample());
            param.setParamIn(resolveParamIn(paramDto.getIn()));
            parameters.add(param);
        }
        return parameters;
    }

    // ─── Enum Resolvers (đã tốt — giữ nguyên) ────────────────────────────────

    private HttpMethod resolveHttpMethod(String value) {
        try {
            return HttpMethod.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("[Persistence] HttpMethod không hợp lệ '{}' — fallback GET.", value);
            return HttpMethod.GET;
        }
    }

    private ParamIn resolveParamIn(String value) {
        try {
            return ParamIn.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("[Persistence] ParamIn không hợp lệ '{}' — fallback QUERY.", value);
            return ParamIn.QUERY;
        }
    }
}
