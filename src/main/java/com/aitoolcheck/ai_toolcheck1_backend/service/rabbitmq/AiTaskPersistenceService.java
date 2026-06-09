package com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiInferenceResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.enums.LogStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ParamIn;
import com.aitoolcheck.ai_toolcheck1_backend.enums.SchemaType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.UsageType;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiParameter;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiSchema;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiSchemaField;
import com.aitoolcheck.ai_toolcheck1_backend.model.EndpointSchemaMap;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiSchemaFieldRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiSchemaRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.EndpointSchemaMapRepository;
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
 * Lưu kết quả AI inference vào Database trong một Transaction.
 *
 * <p>Ngoài ApiEndpoint và ApiParameter, service còn persist ApiSchema, ApiSchemaField,
 * và EndpointSchemaMap nếu AI trả về requestSchema/responseSchema. Failure ở bước
 * schema không ảnh hưởng tới endpoint/parameter đã lưu.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTaskPersistenceService {

    private final ApiEndpointRepository apiEndpointRepository;
    private final SourceFileRepository sourceFileRepository;
    private final ApiSchemaRepository apiSchemaRepository;
    private final ApiSchemaFieldRepository apiSchemaFieldRepository;
    private final EndpointSchemaMapRepository endpointSchemaMapRepository;
    private final LegacyInferenceLogService legacyInferenceLogService;

    @Transactional
    public void persistLegacyInference(SourceProject project,
                                       UUID sourceFileId,
                                       String rawResponse,
                                       String cleanJson,
                                       AiInferenceResultDto dto) {
        log.info("[LegacyCodeReader][Persist] begin");
        try {
            if (dto.getEndpoints().isEmpty()) {
                log.info("[Persistence] No endpoints returned by AI — writing empty audit log.");
                legacyInferenceLogService.createLog(
                        project.getId(), sourceFileId, null,
                        rawResponse, cleanJson, null,
                        LogStatus.SUCCESS, null
                );
                log.info("[LegacyCodeReader][Persist] endpointsSaved=0");
                return;
            }

            SourceFile sourceFileRef = (sourceFileId != null)
                    ? sourceFileRepository.getReferenceById(sourceFileId)
                    : null;

            for (AiInferenceResultDto.EndpointDto epDto : dto.getEndpoints()) {
                ApiEndpoint savedEndpoint = saveApiEndpoint(project, sourceFileRef, epDto);
                persistSchemaIfPresent(project, savedEndpoint, epDto);
                legacyInferenceLogService.createLog(
                        project.getId(),
                        sourceFileId,
                        savedEndpoint.getId(),
                        rawResponse,
                        cleanJson,
                        epDto.getConfidence(),
                        LogStatus.SUCCESS,
                        null
                );
                log.debug("[Persistence] Saved endpoint: {} {}", epDto.getHttpMethod(), epDto.getPath());
            }

            log.info("[LegacyCodeReader][Persist] endpointsSaved={}", dto.getEndpoints().size());
        } catch (Exception e) {
            log.error("[LegacyCodeReader][Persist][ERROR] rootCause={}", e.toString(), e);
            throw e;
        }
    }

    private ApiEndpoint saveApiEndpoint(SourceProject project,
                                        SourceFile sourceFileRef,
                                        AiInferenceResultDto.EndpointDto epDto) {
        ApiEndpoint endpoint = buildApiEndpoint(project, sourceFileRef, epDto);
        endpoint.setApiParameters(buildApiParameters(endpoint, epDto));
        return apiEndpointRepository.save(endpoint);
    }

    private ApiEndpoint buildApiEndpoint(SourceProject project,
                                         SourceFile sourceFileRef,
                                         AiInferenceResultDto.EndpointDto epDto) {
        String normalizedPath = normalizePath(epDto.getPath());
        HttpMethod method = resolveHttpMethod(epDto.getHttpMethod());

        List<ApiEndpoint> existingEndpoints = apiEndpointRepository
                .findByProjectIdAndHttpMethodAndEndpointPath(project.getId(), method, normalizedPath);
                
        ApiEndpoint endpoint = existingEndpoints.isEmpty() ? new ApiEndpoint() : existingEndpoints.get(0);

        if (existingEndpoints.isEmpty()) {
            endpoint.setSourceProject(project);
            endpoint.setCreatedAt(LocalDateTime.now());
        }

        endpoint.setSourceFile(sourceFileRef);
        endpoint.setEndpointPath(normalizedPath);
        endpoint.setHttpMethod(method);
        endpoint.setStableKey(buildStableKey(project.getId(), method, normalizedPath));
        endpoint.setDescription(epDto.getDescription());
        endpoint.setAuthRequired(Boolean.TRUE.equals(epDto.getAuthRequired()));
        
        if (epDto.getSource() != null) {
            endpoint.setControllerName(epDto.getSource().getClassName());
            endpoint.setMethodName(epDto.getSource().getMethodName());
        }
        
        endpoint.setActiveFlag(true);
        endpoint.setStaleFlag(false);
        endpoint.setUpdatedAt(LocalDateTime.now());

        return endpoint;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.trim().replace("\\", "/").replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String buildStableKey(UUID projectId, HttpMethod httpMethod, String endpointPath) {
        return projectId + ":" + (httpMethod == null ? "UNKNOWN" : httpMethod.name()) + ":" + normalizePath(endpointPath);
    }

    private List<ApiParameter> buildApiParameters(ApiEndpoint endpoint,
                                                   AiInferenceResultDto.EndpointDto epDto) {
        List<ApiParameter> parameters = new ArrayList<>();
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

    /**
     * Persist requestSchema và responseSchema nếu AI trả về.
     */
    private void persistSchemaIfPresent(SourceProject project,
                                        ApiEndpoint savedEndpoint,
                                        AiInferenceResultDto.EndpointDto epDto) {
        String methodName = epDto.getSource() != null && epDto.getSource().getMethodName() != null
                ? epDto.getSource().getMethodName() : "unknown";

        if (epDto.getRequestSchema() != null) {
            persistSingleSchema(project, savedEndpoint, epDto.getRequestSchema(),
                    capitalise(methodName) + "Request", SchemaType.REQUEST, UsageType.REQUEST_BODY);
        }

        if (epDto.getResponseSchema() != null) {
            persistSingleSchema(project, savedEndpoint, epDto.getResponseSchema(),
                    capitalise(methodName) + "Response", SchemaType.RESPONSE, UsageType.RESPONSE_BODY);
        }
    }

    /**
     * Upsert ApiSchema (by project + schemaName), insert ApiSchemaField nếu chưa có,
     * tạo EndpointSchemaMap nếu chưa tồn tại.
     */
    private void persistSingleSchema(SourceProject project,
                                     ApiEndpoint savedEndpoint,
                                     AiInferenceResultDto.SchemaDto schemaDto,
                                     String fallbackName,
                                     SchemaType schemaType,
                                     UsageType usageType) {
        String schemaName = (schemaDto.getSchemaName() != null && !schemaDto.getSchemaName().isBlank())
                ? schemaDto.getSchemaName().trim()
                : fallbackName;

        ApiSchema schema = apiSchemaRepository
                .findBySourceProjectIdAndSchemaName(project.getId(), schemaName)
                .orElseGet(() -> {
                    ApiSchema newSchema = ApiSchema.builder()
                            .schemaName(schemaName)
                            .schemaType(schemaType)
                            .description("AI-inferred schema: " + schemaName)
                            .versionNo(1)
                            .sourceProject(project)
                            .build();
                    ApiSchema saved = apiSchemaRepository.save(newSchema);
                    log.debug("[Persistence] Created ApiSchema id={} name='{}'", saved.getId(), schemaName);
                    return saved;
                });

        boolean hasFields = !apiSchemaFieldRepository.findByApiSchemaId(schema.getId()).isEmpty();
        if (!hasFields && schemaDto.getFields() != null && !schemaDto.getFields().isEmpty()) {
            List<ApiSchemaField> fields = new ArrayList<>();
            for (AiInferenceResultDto.FieldDto fieldDto : schemaDto.getFields()) {
                if (fieldDto.getFieldName() == null || fieldDto.getFieldName().isBlank()) {
                    continue;
                }
                fields.add(ApiSchemaField.builder()
                        .fieldName(fieldDto.getFieldName().trim())
                        .dataType(fieldDto.getDataType() != null ? fieldDto.getDataType() : "Object")
                        .requiredFlag(Boolean.TRUE.equals(fieldDto.getRequired()))
                        .nullableFlag(fieldDto.getNullable() != null ? fieldDto.getNullable() : true)
                        .apiSchema(schema)
                        .build());
            }
            if (!fields.isEmpty()) {
                apiSchemaFieldRepository.saveAll(fields);
                log.debug("[Persistence] Saved {} field(s) for schema '{}'", fields.size(), schemaName);
            }
        }

        boolean mapExists = endpointSchemaMapRepository
                .findByApiEndpointId(savedEndpoint.getId())
                .stream()
                .anyMatch(m -> m.getUsageType() == usageType
                        && m.getApiSchema() != null
                        && m.getApiSchema().getId().equals(schema.getId()));

        if (!mapExists) {
            endpointSchemaMapRepository.save(EndpointSchemaMap.builder()
                    .usageType(usageType)
                    .apiEndpoint(savedEndpoint)
                    .apiSchema(schema)
                    .build());
            log.debug("[Persistence] Created EndpointSchemaMap endpoint={} schema='{}' usage={}",
                    savedEndpoint.getId(), schemaName, usageType);
        }
    }

    private HttpMethod resolveHttpMethod(String value) {
        try {
            return HttpMethod.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("[Persistence] Invalid HttpMethod '{}' — fallback GET.", value);
            return HttpMethod.GET;
        }
    }

    private ParamIn resolveParamIn(String value) {
        try {
            return ParamIn.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("[Persistence] Invalid ParamIn '{}' — fallback QUERY.", value);
            return ParamIn.QUERY;
        }
    }

    private String capitalise(String s) {
        if (s == null || s.isBlank()) return "Unknown";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
