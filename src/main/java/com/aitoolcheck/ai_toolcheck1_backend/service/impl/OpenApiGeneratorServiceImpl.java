package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.openapi.res.OpenApiGenerateResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.DocumentType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ParamIn;
import com.aitoolcheck.ai_toolcheck1_backend.enums.UsageType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.*;
import com.aitoolcheck.ai_toolcheck1_backend.repository.*;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiMetadataCleanupService;
import com.aitoolcheck.ai_toolcheck1_backend.service.OpenApiGeneratorService;
import com.aitoolcheck.ai_toolcheck1_backend.service.OpenApiMetadataEnhancerService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenApiGeneratorServiceImpl implements OpenApiGeneratorService {

    private final ObjectMapper objectMapper;
    private final SourceProjectRepository sourceProjectRepository;
    private final ApiEndpointRepository apiEndpointRepository;
    private final ApiParameterRepository apiParameterRepository;
    private final ApiSchemaRepository apiSchemaRepository;
    private final ApiSchemaFieldRepository apiSchemaFieldRepository;
    private final EndpointSchemaMapRepository endpointSchemaMapRepository;
    private final ApiDocumentRepository apiDocumentRepository;
    private final ApiDocumentVersionRepository apiDocumentVersionRepository;
    private final ProjectAccessService projectAccessService;
    private final ApiMetadataCleanupService apiMetadataCleanupService;
    private final OpenApiMetadataEnhancerService openApiMetadataEnhancerService;

    // -------------------------------------------------------------------------
    // Public methods
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public Map<String, Object> generateOpenApiJson(UUID projectId) {
        log.info("Generating OpenAPI JSON for projectId={}", projectId);
        projectAccessService.requireCanViewProject(projectId);

        SourceProject project = sourceProjectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Source project not found with id: " + projectId));

        // Call cleanup before querying to ensure fresh data
        apiMetadataCleanupService.cleanupProjectApiMetadata(projectId);

        List<ApiEndpoint> endpoints = apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId);
        if (endpoints.isEmpty()) {
            throw new BadRequestException("No API endpoints found for project id: " + projectId);
        }

        List<ApiSchema> schemas = apiSchemaRepository.findBySourceProjectId(projectId);

        return buildOpenApiDocument(project, endpoints, schemas);
    }

    @Override
    @Transactional
    public OpenApiGenerateResponse generateAndSaveOpenApi(UUID projectId) {
        log.info("Generating and saving OpenAPI JSON for projectId={}", projectId);
        projectAccessService.requireCanGenerateDocs(projectId);

        Map<String, Object> openApiMap = generateOpenApiJson(projectId);

        SourceProject project = sourceProjectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Source project not found with id: " + projectId));

        apiMetadataCleanupService.cleanupProjectApiMetadata(projectId);
        List<ApiEndpoint> endpoints = apiEndpointRepository.findBySourceProjectIdAndActiveFlagTrueAndStaleFlagFalse(projectId);
        List<ApiSchema> schemas = apiSchemaRepository.findBySourceProjectId(projectId);

        String contentJson = serializeToJson(openApiMap, projectId);

        // Find or create the project-level ApiDocument
        ApiDocument apiDocument = apiDocumentRepository
                .findBySourceProjectId(projectId)
                .orElseGet(() -> apiDocumentRepository.save(ApiDocument.builder()
                        .sourceProject(project)
                        .documentName(project.getProjectName() + " OpenAPI")
                        .documentType(DocumentType.OPENAPI_3)
                        .currentVersionNo(0)
                        .publishedFlag(false)
                        .staleFlag(false)
                        .build()));

        int nextVersionNo = apiDocumentVersionRepository
                .findTopByApiDocumentIdOrderByVersionNoDesc(apiDocument.getId())
                .map(v -> v.getVersionNo() + 1)
                .orElse(1);

        ApiDocumentVersion savedVersion = apiDocumentVersionRepository.save(
                ApiDocumentVersion.builder()
                        .apiDocument(apiDocument)
                        .versionNo(nextVersionNo)
                        .contentJson(contentJson)
                        .summary("Generated OpenAPI 3.0 document")
                        .description("Generated from parsed API metadata")
                        .aiEnrichedFlag(false)
                        .build());

        apiDocument.setCurrentVersionNo(nextVersionNo);
        apiDocument.setStaleFlag(false);
        ApiDocument savedDocument = apiDocumentRepository.save(apiDocument);

        log.info("Saved OpenAPI version={} for projectId={}, docId={}, versionId={}",
                nextVersionNo, projectId, savedDocument.getId(), savedVersion.getId());

        return OpenApiGenerateResponse.builder()
                .projectId(projectId)
                .apiDocumentId(savedDocument.getId())
                .apiDocumentVersionId(savedVersion.getId())
                .versionNo(nextVersionNo)
                .totalEndpoints(endpoints.size())
                .totalSchemas(schemas.size())
                .publishedFlag(savedDocument.getPublishedFlag())
                .summary("OpenAPI 3.0 document generated with "
                        + endpoints.size() + " endpoint(s) and "
                        + schemas.size() + " schema(s).")
                .build();
    }

    // -------------------------------------------------------------------------
    // Document assembly
    // -------------------------------------------------------------------------

    private Map<String, Object> buildOpenApiDocument(
            SourceProject project, List<ApiEndpoint> endpoints, List<ApiSchema> schemas) {

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("openapi", "3.0.3");
        root.put("info", buildInfo(project));
        root.put("paths", buildPaths(endpoints));
        root.put("components", Map.of("schemas", buildComponentSchemas(schemas)));
        return root;
    }

    private Map<String, Object> buildInfo(SourceProject project) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", project.getProjectName() != null ? project.getProjectName() : "Untitled Project");
        info.put("version", "1.0.0");
        if (project.getDescription() != null && !project.getDescription().isBlank()) {
            info.put("description", project.getDescription());
        }
        return info;
    }

    // -------------------------------------------------------------------------
    // Paths
    // -------------------------------------------------------------------------

    private Map<String, Object> buildPaths(List<ApiEndpoint> endpoints) {
        Set<String> usedOperationIds = new LinkedHashSet<>();
        Map<String, Object> paths = new LinkedHashMap<>();

        for (ApiEndpoint endpoint : endpoints) {
            String pathKey = endpoint.getEndpointPath();
            if (pathKey == null || pathKey.isBlank()) {
                log.warn("Skipping endpoint id={} — endpointPath is blank", endpoint.getId());
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> pathItem = (Map<String, Object>)
                    paths.computeIfAbsent(pathKey, k -> new LinkedHashMap<>());

            pathItem.put(resolveHttpMethod(endpoint), buildOperation(endpoint, usedOperationIds));
        }

        return paths;
    }

    private String resolveHttpMethod(ApiEndpoint endpoint) {
        return endpoint.getHttpMethod() == null
                ? "get"
                : endpoint.getHttpMethod().name().toLowerCase();
    }

    // -------------------------------------------------------------------------
    // Operation
    // -------------------------------------------------------------------------

    private Map<String, Object> buildOperation(ApiEndpoint endpoint, Set<String> usedOperationIds) {
        Map<String, Object> operation = new LinkedHashMap<>();
        List<ApiParameter> params = apiParameterRepository.findByApiEndpointId(endpoint.getId());

        String tag = openApiMetadataEnhancerService.inferTag(endpoint);
        operation.put("tags", List.of(tag));

        String summary = openApiMetadataEnhancerService.inferSummary(endpoint, params);
        if (summary != null) {
            operation.put("summary", summary);
        }

        String description = openApiMetadataEnhancerService.inferDescription(endpoint, summary);
        if (description != null && !description.isBlank()) {
            operation.put("description", description);
        }

        String opId = openApiMetadataEnhancerService.inferOperationId(endpoint, params, usedOperationIds);
        operation.put("operationId", opId);

        if (Boolean.TRUE.equals(endpoint.getDeprecatedFlag())) {
            operation.put("deprecated", true);
        }

        List<Map<String, Object>> parameters = buildParameters(endpoint, params);
        if (!parameters.isEmpty()) {
            operation.put("parameters", parameters);
        }

        Map<String, Object> requestBody = buildRequestBody(endpoint);
        if (requestBody != null) {
            operation.put("requestBody", requestBody);
        }

        operation.put("responses", buildResponses(endpoint, summary));
        return operation;
    }

    // -------------------------------------------------------------------------
    // Parameters
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> buildParameters(ApiEndpoint endpoint, List<ApiParameter> params) {
        List<Map<String, Object>> parameters = new ArrayList<>();

        for (ApiParameter param : params) {
            ParamIn paramIn = param.getParamIn();

            // Skip BODY (represented by requestBody) and FORM (phase 1)
            if (paramIn == null || paramIn == ParamIn.BODY || paramIn == ParamIn.FORM) {
                continue;
            }

            Map<String, Object> paramObj = new LinkedHashMap<>();
            paramObj.put("name", param.getParamName() != null ? param.getParamName() : "");
            paramObj.put("in", mapParamIn(paramIn));
            // PATH params are always required
            paramObj.put("required", paramIn == ParamIn.PATH || Boolean.TRUE.equals(param.getRequiredFlag()));
            paramObj.put("schema", convertJavaTypeToOpenApiSchema(param.getDataType()));

            String pDesc = openApiMetadataEnhancerService.enrichParameterDescription(param);
            if (pDesc != null && !pDesc.isBlank()) {
                paramObj.put("description", pDesc);
            }

            if (param.getExampleValue() != null && !param.getExampleValue().isBlank()) {
                paramObj.put("example", param.getExampleValue());
            }

            parameters.add(paramObj);
        }

        return parameters;
    }

    private String mapParamIn(ParamIn paramIn) {
        return switch (paramIn) {
            case PATH   -> "path";
            case QUERY  -> "query";
            case HEADER -> "header";
            case COOKIE -> "cookie";
            default     -> "query";
        };
    }

    // -------------------------------------------------------------------------
    // RequestBody
    // -------------------------------------------------------------------------

    private Map<String, Object> buildRequestBody(ApiEndpoint endpoint) {
        Map<String, Object> requestBody = null;

        for (EndpointSchemaMap map : endpointSchemaMapRepository.findByApiEndpointId(endpoint.getId())) {
            if (map.getUsageType() != UsageType.REQUEST_BODY || map.getApiSchema() == null) continue;

            String schemaName = map.getApiSchema().getSchemaName();
            if (schemaName == null || schemaName.isBlank()) continue;

            requestBody = new LinkedHashMap<>();
            requestBody.put("required", true);

            Map<String, Object> mediaType = buildMediaTypeWithExample(
                    schemaRef(schemaName),
                    endpoint.getExampleRequestJson(),
                    "requestBody example",
                    endpoint.getId()
            );
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("application/json", mediaType);
            requestBody.put("content", content);
            break;
        }

        if (requestBody == null && endpoint.getExampleRequestJson() != null
                && !endpoint.getExampleRequestJson().isBlank()) {
            Object parsedExample = parseJsonSafely(endpoint.getExampleRequestJson(),
                    "exampleRequestJson", endpoint.getId());
            if (parsedExample != null) {
                Map<String, Object> mediaType = new LinkedHashMap<>();
                mediaType.put("schema", objectSchema());
                mediaType.put("example", parsedExample);
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("application/json", mediaType);
                requestBody = new LinkedHashMap<>();
                requestBody.put("required", true);
                requestBody.put("content", content);
            }
        }

        return requestBody;
    }

    // -------------------------------------------------------------------------
    // Responses
    // -------------------------------------------------------------------------

    private Map<String, Object> buildResponses(ApiEndpoint endpoint, String summary) {
        Map<String, Object> responses = new LinkedHashMap<>();
        Map<String, String> responseDescs = openApiMetadataEnhancerService.getResponseDescriptions(endpoint, summary);

        Map<String, Object> response200 = new LinkedHashMap<>();
        response200.put("description", responseDescs.getOrDefault("200", "OK"));

        boolean schemaSet = false;
        for (EndpointSchemaMap map : endpointSchemaMapRepository.findByApiEndpointId(endpoint.getId())) {
            if (map.getUsageType() != UsageType.RESPONSE_BODY || map.getApiSchema() == null) continue;

            String schemaName = map.getApiSchema().getSchemaName();
            if (schemaName != null && !schemaName.isBlank()) {
                Map<String, Object> mediaType = buildMediaTypeWithExample(
                        schemaRef(schemaName),
                        endpoint.getExampleResponseJson(),
                        "exampleResponseJson",
                        endpoint.getId()
                );
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("application/json", mediaType);
                response200.put("content", content);
                schemaSet = true;
                break;
            }
        }

        if (!schemaSet && endpoint.getExampleResponseJson() != null
                && !endpoint.getExampleResponseJson().isBlank()) {
            Object parsedExample = parseJsonSafely(endpoint.getExampleResponseJson(),
                    "exampleResponseJson", endpoint.getId());
            if (parsedExample != null) {
                Map<String, Object> mediaType = new LinkedHashMap<>();
                mediaType.put("schema", objectSchema());
                mediaType.put("example", parsedExample);
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("application/json", mediaType);
                response200.put("content", content);
            }
        }

        responses.put("200", response200);

        for (Map.Entry<String, String> entry : responseDescs.entrySet()) {
            String statusCode = entry.getKey();
            if ("200".equals(statusCode)) continue;

            Map<String, Object> errorResponse = new LinkedHashMap<>();
            errorResponse.put("description", entry.getValue());
            responses.put(statusCode, errorResponse);
        }

        return responses;
    }

    // -------------------------------------------------------------------------
    // Component schemas
    // -------------------------------------------------------------------------

    private Map<String, Object> buildComponentSchemas(List<ApiSchema> schemas) {
        Map<String, Object> componentSchemas = new LinkedHashMap<>();

        for (ApiSchema schema : schemas) {
            String schemaName = schema.getSchemaName();
            if (schemaName == null || schemaName.isBlank()) {
                log.warn("Skipping schema id={} — schemaName is blank", schema.getId());
                continue;
            }

            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> requiredFields = new ArrayList<>();

            for (ApiSchemaField field : apiSchemaFieldRepository.findByApiSchemaId(schema.getId())) {
                if (field.getFieldName() == null || field.getFieldName().isBlank()) continue;

                Map<String, Object> fieldSchema = convertJavaTypeToOpenApiSchema(field.getDataType());

                if (Boolean.TRUE.equals(field.getNullableFlag())) {
                    fieldSchema = new LinkedHashMap<>(fieldSchema); // defensive copy before mutation
                    fieldSchema.put("nullable", true);
                }

                properties.put(field.getFieldName(), fieldSchema);

                if (Boolean.TRUE.equals(field.getRequiredFlag())) {
                    requiredFields.add(field.getFieldName());
                }
            }

            Map<String, Object> schemaObj = new LinkedHashMap<>();
            schemaObj.put("type", "object");
            schemaObj.put("properties", properties);
            if (!requiredFields.isEmpty()) {
                schemaObj.put("required", requiredFields);
            }

            componentSchemas.put(schemaName, schemaObj);
        }

        return componentSchemas;
    }

    // -------------------------------------------------------------------------
    // Java type → OpenAPI schema
    // -------------------------------------------------------------------------

    private Map<String, Object> convertJavaTypeToOpenApiSchema(String javaType) {
        if (javaType == null || javaType.isBlank()) return objectSchema();

        String type = javaType.trim();

        // Handle generic collections: List<X>, Set<X>, Collection<X>
        String inner = extractCollectionInner(type);
        if (inner != null) {
            Map<String, Object> array = new LinkedHashMap<>();
            array.put("type", "array");
            array.put("items", convertJavaTypeToOpenApiSchema(inner));
            return array;
        }

        return switch (type) {
            case "String"                   -> stringSchema(null);
            case "Integer", "int"           -> integerSchema("int32");
            case "Long",    "long"          -> integerSchema("int64");
            case "Boolean", "boolean"       -> booleanSchema();
            case "Double",  "double"        -> numberSchema("double");
            case "Float",   "float"         -> numberSchema("float");
            case "BigDecimal"               -> numberSchema(null);
            case "BigInteger"               -> integerSchema(null);
            case "LocalDate"                -> stringSchema("date");
            case "LocalDateTime", "Date"    -> stringSchema("date-time");
            case "UUID"                     -> stringSchemaWithFormat("uuid");
            case "Object", "T"             -> objectSchema();
            default                         -> resolveCustomType(type);
        };
    }

    private String extractCollectionInner(String type) {
        if (type.startsWith("List<")       && type.endsWith(">")) return type.substring(5, type.length() - 1).trim();
        if (type.startsWith("Set<")        && type.endsWith(">")) return type.substring(4, type.length() - 1).trim();
        if (type.startsWith("Collection<") && type.endsWith(">")) return type.substring(11, type.length() - 1).trim();
        return null;
    }

    // PascalCase → $ref; anything else → object
    private Map<String, Object> resolveCustomType(String type) {
        if (!type.isEmpty() && Character.isUpperCase(type.charAt(0)) && !type.contains(" ")) {
            return schemaRef(type);
        }
        return objectSchema();
    }

    // -------------------------------------------------------------------------
    // Schema shape helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> stringSchema(String format) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "string");
        if (format != null) s.put("format", format);
        return s;
    }

    // Used only for UUID — format keyword differs from date formats
    private Map<String, Object> stringSchemaWithFormat(String format) {
        return stringSchema(format);
    }

    private Map<String, Object> integerSchema(String format) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "integer");
        if (format != null) s.put("format", format);
        return s;
    }

    private Map<String, Object> numberSchema(String format) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "number");
        if (format != null) s.put("format", format);
        return s;
    }

    private Map<String, Object> booleanSchema() {
        return new LinkedHashMap<>(Map.of("type", "boolean"));
    }

    private Map<String, Object> objectSchema() {
        return new LinkedHashMap<>(Map.of("type", "object"));
    }

    private Map<String, Object> schemaRef(String schemaName) {
        return new LinkedHashMap<>(Map.of("$ref", "#/components/schemas/" + schemaName));
    }

    private Map<String, Object> jsonContent(Map<String, Object> schema) {
        Map<String, Object> mediaType = new LinkedHashMap<>();
        mediaType.put("schema", schema);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("application/json", mediaType);
        return content;
    }

    // -------------------------------------------------------------------------
    // JSON example helpers
    // -------------------------------------------------------------------------

    /**
     * Build media type map với schema ref và optional example.
     * Nếu exampleJson không hợp lệ, log warning và bỏ qua — không crash generator.
     */
    private Map<String, Object> buildMediaTypeWithExample(Map<String, Object> schemaObj,
                                                           String exampleJson,
                                                           String fieldLabel,
                                                           java.util.UUID endpointId) {
        Map<String, Object> mediaType = new LinkedHashMap<>();
        mediaType.put("schema", schemaObj);
        if (exampleJson != null && !exampleJson.isBlank()) {
            Object parsed = parseJsonSafely(exampleJson, fieldLabel, endpointId);
            if (parsed != null) {
                mediaType.put("example", parsed);
            }
        }
        return mediaType;
    }

    /**
     * Parse JSON string an toàn. Trả về Object nếu thành công, null nếu lỗi.
     * Không throw — chỉ log warning.
     */
    private Object parseJsonSafely(String json, String fieldLabel, java.util.UUID endpointId) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("[OpenApiGenerator] Could not parse {} for endpointId={} — skipping example. Reason: {}",
                    fieldLabel, endpointId, e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private String serializeToJson(Map<String, Object> map, UUID projectId) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(map);
        } catch (Exception e) {
            log.error("Failed to serialize OpenAPI JSON for projectId={}", projectId, e);
            throw new RuntimeException("Failed to serialize OpenAPI JSON: " + e.getMessage(), e);
        }
    }
}
