package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apimetadata.res.ApiMetadataParseResultResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.FileType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ParamIn;
import com.aitoolcheck.ai_toolcheck1_backend.enums.SchemaType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.UsageType;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiParameter;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiSchema;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiSchemaField;
import com.aitoolcheck.ai_toolcheck1_backend.model.EndpointSchemaMap;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiParameterRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiSchemaFieldRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiSchemaRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.EndpointSchemaMapRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceFileRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceProjectRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.ApiMetadataParserService;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiMetadataParserServiceImpl implements ApiMetadataParserService {

    private final SourceProjectRepository sourceProjectRepository;
    private final SourceFileRepository sourceFileRepository;
    private final ApiEndpointRepository apiEndpointRepository;
    private final ApiParameterRepository apiParameterRepository;
    private final ApiSchemaRepository apiSchemaRepository;
    private final ApiSchemaFieldRepository apiSchemaFieldRepository;
    private final EndpointSchemaMapRepository endpointSchemaMapRepository;

    @Override
    @Transactional
    public ApiMetadataParseResultResponse parseProject(UUID projectId) {
        SourceProject sourceProject = sourceProjectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Source project not found with id: " + projectId));

        List<SourceFile> sourceFiles = sourceFileRepository.findBySourceProjectId(projectId);
        List<SourceFile> controllerFiles = sourceFiles.stream()
                .filter(file -> file.getFileType() == FileType.CONTROLLER)
                .filter(file -> file.getSourceContent() != null && !file.getSourceContent().isBlank())
                .toList();

        if (controllerFiles.isEmpty()) {
            throw new BadRequestException("No controller source files found for project id: " + projectId);
        }

        deleteExistingMetadata(projectId);

        ParseCounters counters = new ParseCounters();
        Map<String, ApiSchema> schemaCache = new HashMap<>();
        Set<String> schemaFieldsCreated = new HashSet<>();

        for (SourceFile controllerFile : controllerFiles) {
            try {
                CompilationUnit compilationUnit = StaticJavaParser.parse(controllerFile.getSourceContent());
                parseControllerFile(sourceProject, controllerFile, sourceFiles, compilationUnit, schemaCache, schemaFieldsCreated, counters);
                counters.parsedControllerFiles++;
            } catch (Exception e) {
                log.warn(
                        "Failed to parse controller metadata. projectId={}, sourceFileId={}, fileName={}, reason={}",
                        projectId,
                        controllerFile.getId(),
                        controllerFile.getFileName(),
                        e.getMessage()
                );
            }
        }

        if (counters.parsedControllerFiles == 0) {
            throw new BadRequestException("Failed to parse all controller source files for project id: " + projectId);
        }

        return buildResponse(projectId, controllerFiles.size(), counters);
    }

    private void deleteExistingMetadata(UUID projectId) {
        endpointSchemaMapRepository.deleteByApiEndpoint_SourceProject_Id(projectId);
        apiParameterRepository.deleteByApiEndpoint_SourceProject_Id(projectId);
        apiSchemaFieldRepository.deleteByApiSchema_SourceProject_Id(projectId);
        apiEndpointRepository.deleteBySourceProjectId(projectId);
        apiSchemaRepository.deleteBySourceProjectId(projectId);
    }

    private void parseControllerFile(
            SourceProject sourceProject,
            SourceFile sourceFile,
            List<SourceFile> allFiles,
            CompilationUnit compilationUnit,
            Map<String, ApiSchema> schemaCache,
            Set<String> schemaFieldsCreated,
            ParseCounters counters
    ) {
        List<ClassOrInterfaceDeclaration> classes = compilationUnit.findAll(ClassOrInterfaceDeclaration.class);
        List<ClassOrInterfaceDeclaration> controllerClasses = classes.stream()
                .filter(declaration -> hasAnnotation(declaration, "RestController") || hasAnnotation(declaration, "Controller"))
                .toList();

        if (controllerClasses.isEmpty()) {
            controllerClasses = classes.stream()
                    .filter(declaration -> declaration.getMethods().stream().anyMatch(method -> !extractHttpMethods(method).isEmpty()))
                    .toList();
        }

        for (ClassOrInterfaceDeclaration controllerClass : controllerClasses) {
            parseControllerClass(sourceProject, sourceFile, allFiles, controllerClass, schemaCache, schemaFieldsCreated, counters);
        }
    }

    private void parseControllerClass(
            SourceProject sourceProject,
            SourceFile sourceFile,
            List<SourceFile> allFiles,
            ClassOrInterfaceDeclaration controllerClass,
            Map<String, ApiSchema> schemaCache,
            Set<String> schemaFieldsCreated,
            ParseCounters counters
    ) {
        String controllerName = controllerClass.getNameAsString();
        String tagName = buildTagName(controllerName);
        String basePath = extractRequestMappingPath(controllerClass);

        for (MethodDeclaration method : controllerClass.getMethods()) {
            List<HttpMethod> httpMethods = extractHttpMethods(method);
            if (httpMethods.isEmpty()) {
                continue;
            }

            String methodPath = extractMappingPath(method);
            String endpointPath = combinePaths(basePath, methodPath);

            for (HttpMethod httpMethod : httpMethods) {
                ApiEndpoint endpoint = createEndpoint(sourceProject, sourceFile, controllerClass, method, httpMethod, endpointPath, tagName);
                ApiEndpoint savedEndpoint = apiEndpointRepository.save(endpoint);
                counters.totalEndpoints++;

                parseParameters(savedEndpoint, method, counters);
                parseRequestSchemas(sourceProject, savedEndpoint, method, allFiles, schemaCache, schemaFieldsCreated, counters);
                parseResponseSchema(sourceProject, savedEndpoint, method, allFiles, schemaCache, schemaFieldsCreated, counters);
            }
        }
    }

    private ApiEndpoint createEndpoint(
            SourceProject sourceProject,
            SourceFile sourceFile,
            ClassOrInterfaceDeclaration controllerClass,
            MethodDeclaration method,
            HttpMethod httpMethod,
            String endpointPath,
            String tagName
    ) {
        LocalDateTime now = LocalDateTime.now();

        return ApiEndpoint.builder()
                .controllerName(controllerClass.getNameAsString())
                .methodName(method.getNameAsString())
                .httpMethod(httpMethod)
                .endpointPath(endpointPath)
                .operationId(method.getNameAsString())
                .tagName(tagName)
                .authRequired(isAuthRequired(controllerClass, method))
                .deprecatedFlag(isDeprecated(controllerClass, method))
                .createdAt(now)
                .updatedAt(now)
                .sourceProject(sourceProject)
                .sourceFile(sourceFile)
                .build();
    }

    private void parseParameters(ApiEndpoint endpoint, MethodDeclaration method, ParseCounters counters) {
        List<ApiParameter> parameters = new ArrayList<>();

        for (Parameter methodParameter : method.getParameters()) {
            Optional<ParamIn> paramIn = resolveParamIn(methodParameter);
            if (paramIn.isEmpty()) {
                continue;
            }

            ApiParameter apiParameter = ApiParameter.builder()
                    .paramName(resolveParameterName(methodParameter, paramIn.get()))
                    .paramIn(paramIn.get())
                    .dataType(methodParameter.getType().asString())
                    .requiredFlag(resolveRequiredFlag(methodParameter, paramIn.get()))
                    .exampleValue(null)
                    .apiEndpoint(endpoint)
                    .build();

            parameters.add(apiParameter);
        }

        if (!parameters.isEmpty()) {
            apiParameterRepository.saveAll(parameters);
            counters.totalParameters += parameters.size();
        }
    }

    private void parseRequestSchemas(
            SourceProject project,
            ApiEndpoint endpoint,
            MethodDeclaration method,
            List<SourceFile> allFiles,
            Map<String, ApiSchema> schemaCache,
            Set<String> schemaFieldsCreated,
            ParseCounters counters
    ) {
        for (Parameter parameter : method.getParameters()) {
            if (!hasAnnotation(parameter, "RequestBody")) {
                continue;
            }

            String schemaName = cleanTypeName(parameter.getType().asString());
            if (isSimpleType(schemaName)) {
                continue;
            }

            ApiSchema schema = createOrGetSchema(
                    project,
                    schemaName,
                    SchemaType.REQUEST,
                    "Request body schema for " + schemaName,
                    allFiles,
                    schemaCache,
                    schemaFieldsCreated,
                    counters
            );
            createEndpointSchemaMap(endpoint, schema, UsageType.REQUEST_BODY, counters);
        }
    }

    private void parseResponseSchema(
            SourceProject project,
            ApiEndpoint endpoint,
            MethodDeclaration method,
            List<SourceFile> allFiles,
            Map<String, ApiSchema> schemaCache,
            Set<String> schemaFieldsCreated,
            ParseCounters counters
    ) {
        String schemaName = unwrapResponseType(method.getType().asString());
        if (isSimpleType(schemaName)) {
            return;
        }

        ApiSchema schema = createOrGetSchema(
                project,
                schemaName,
                SchemaType.RESPONSE,
                "Response body schema for " + schemaName,
                allFiles,
                schemaCache,
                schemaFieldsCreated,
                counters
        );
        createEndpointSchemaMap(endpoint, schema, UsageType.RESPONSE_BODY, counters);
    }

    private ApiSchema createOrGetSchema(
            SourceProject project,
            String schemaName,
            SchemaType schemaType,
            String description,
            List<SourceFile> allFiles,
            Map<String, ApiSchema> schemaCache,
            Set<String> schemaFieldsCreated,
            ParseCounters counters
    ) {
        ApiSchema cachedSchema = schemaCache.get(schemaName);
        if (cachedSchema != null) {
            return cachedSchema;
        }

        ApiSchema schema = apiSchemaRepository.findBySourceProjectIdAndSchemaName(project.getId(), schemaName)
                .orElseGet(() -> {
                    ApiSchema newSchema = ApiSchema.builder()
                            .schemaName(schemaName)
                            .schemaType(schemaType)
                            .description(description)
                            .versionNo(1)
                            .sourceProject(project)
                            .build();
                    counters.totalSchemas++;
                    return apiSchemaRepository.save(newSchema);
                });

        schemaCache.put(schemaName, schema);
        createSchemaFields(project.getId(), schema, allFiles, schemaFieldsCreated, counters);
        return schema;
    }

    private void createSchemaFields(
            UUID projectId,
            ApiSchema schema,
            List<SourceFile> allFiles,
            Set<String> schemaFieldsCreated,
            ParseCounters counters
    ) {
        String schemaName = schema.getSchemaName();
        if (!schemaFieldsCreated.add(schemaName)) {
            return;
        }

        findSchemaSourceFile(projectId, schemaName, allFiles).ifPresent(sourceFile -> {
            try {
                CompilationUnit compilationUnit = StaticJavaParser.parse(sourceFile.getSourceContent());
                List<ApiSchemaField> fields = new ArrayList<>();

                for (ClassOrInterfaceDeclaration declaration : compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
                    if (!declaration.getNameAsString().equals(schemaName)) {
                        continue;
                    }

                    for (FieldDeclaration fieldDeclaration : declaration.getFields()) {
                        fieldDeclaration.getVariables().forEach(variable -> fields.add(ApiSchemaField.builder()
                                .fieldName(variable.getNameAsString())
                                .dataType(variable.getType().asString())
                                .requiredFlag(Boolean.FALSE)
                                .nullableFlag(Boolean.TRUE)
                                .apiSchema(schema)
                                .build()));
                    }
                }

                if (!fields.isEmpty()) {
                    apiSchemaFieldRepository.saveAll(fields);
                    counters.totalSchemaFields += fields.size();
                }
            } catch (Exception e) {
                log.warn(
                        "Failed to parse schema fields. projectId={}, schemaName={}, sourceFileId={}, reason={}",
                        projectId,
                        schemaName,
                        sourceFile.getId(),
                        e.getMessage()
                );
            }
        });
    }

    private void createEndpointSchemaMap(ApiEndpoint endpoint, ApiSchema schema, UsageType usageType, ParseCounters counters) {
        EndpointSchemaMap endpointSchemaMap = EndpointSchemaMap.builder()
                .usageType(usageType)
                .apiEndpoint(endpoint)
                .apiSchema(schema)
                .build();
        endpointSchemaMapRepository.save(endpointSchemaMap);
        counters.totalEndpointSchemaMaps++;
    }

    private Optional<ParamIn> resolveParamIn(Parameter parameter) {
        if (hasAnnotation(parameter, "PathVariable")) {
            return Optional.of(ParamIn.PATH);
        }
        if (hasAnnotation(parameter, "RequestParam")) {
            return Optional.of(ParamIn.QUERY);
        }
        if (hasAnnotation(parameter, "RequestHeader")) {
            return Optional.of(ParamIn.HEADER);
        }
        if (hasAnnotation(parameter, "RequestBody")) {
            return Optional.of(ParamIn.BODY);
        }
        if (hasAnnotation(parameter, "CookieValue")) {
            return Optional.of(ParamIn.COOKIE);
        }
        if (hasAnnotation(parameter, "ModelAttribute")) {
            return Optional.of(ParamIn.FORM);
        }
        return Optional.empty();
    }

    private String resolveParameterName(Parameter parameter, ParamIn paramIn) {
        String annotationName = switch (paramIn) {
            case PATH -> "PathVariable";
            case QUERY -> "RequestParam";
            case HEADER -> "RequestHeader";
            case BODY -> "RequestBody";
            case COOKIE -> "CookieValue";
            case FORM -> "ModelAttribute";
        };

        return parameter.getAnnotations().stream()
                .filter(annotation -> annotationNameMatches(annotation, annotationName))
                .findFirst()
                .flatMap(this::extractAnnotationName)
                .orElse(parameter.getNameAsString());
    }

    private Boolean resolveRequiredFlag(Parameter parameter, ParamIn paramIn) {
        Optional<Boolean> explicitRequired = annotationForParam(parameter, paramIn)
                .flatMap(this::extractRequiredFlag);
        if (explicitRequired.isPresent()) {
            return explicitRequired.get();
        }

        return switch (paramIn) {
            case PATH, BODY -> Boolean.TRUE;
            case QUERY, HEADER, COOKIE, FORM -> Boolean.FALSE;
        };
    }

    private Optional<AnnotationExpr> annotationForParam(Parameter parameter, ParamIn paramIn) {
        String annotationName = switch (paramIn) {
            case PATH -> "PathVariable";
            case QUERY -> "RequestParam";
            case HEADER -> "RequestHeader";
            case BODY -> "RequestBody";
            case COOKIE -> "CookieValue";
            case FORM -> "ModelAttribute";
        };
        return parameter.getAnnotations().stream()
                .filter(annotation -> annotationNameMatches(annotation, annotationName))
                .findFirst();
    }

    private Optional<SourceFile> findSchemaSourceFile(UUID projectId, String schemaName, List<SourceFile> allFiles) {
        return allFiles.stream()
                .filter(file -> file.getSourceProject() != null && projectId.equals(file.getSourceProject().getId()))
                .filter(file -> schemaName.equals(file.getClassName()))
                .filter(file -> file.getSourceContent() != null && !file.getSourceContent().isBlank())
                .findFirst();
    }

    private String extractRequestMappingPath(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .filter(annotation -> annotationNameMatches(annotation, "RequestMapping"))
                .findFirst()
                .flatMap(this::extractStringValueFromAnnotation)
                .orElse("");
    }

    private String extractMappingPath(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .filter(this::isMappingAnnotation)
                .findFirst()
                .flatMap(this::extractStringValueFromAnnotation)
                .orElse("");
    }

    private List<HttpMethod> extractHttpMethods(MethodDeclaration method) {
        List<HttpMethod> methods = new ArrayList<>();

        for (AnnotationExpr annotation : method.getAnnotations()) {
            if (annotationNameMatches(annotation, "GetMapping")) {
                methods.add(HttpMethod.GET);
            } else if (annotationNameMatches(annotation, "PostMapping")) {
                methods.add(HttpMethod.POST);
            } else if (annotationNameMatches(annotation, "PutMapping")) {
                methods.add(HttpMethod.PUT);
            } else if (annotationNameMatches(annotation, "PatchMapping")) {
                methods.add(HttpMethod.PATCH);
            } else if (annotationNameMatches(annotation, "DeleteMapping")) {
                methods.add(HttpMethod.DELETE);
            } else if (annotationNameMatches(annotation, "RequestMapping")) {
                methods.addAll(extractRequestMethods(annotation));
            }
        }

        return methods;
    }

    private List<HttpMethod> extractRequestMethods(AnnotationExpr annotation) {
        if (!annotation.isNormalAnnotationExpr()) {
            return List.of();
        }

        return annotation.asNormalAnnotationExpr().getPairs().stream()
                .filter(pair -> pair.getNameAsString().equals("method"))
                .findFirst()
                .map(MemberValuePair::getValue)
                .map(this::extractHttpMethodsFromExpression)
                .orElse(List.of());
    }

    private List<HttpMethod> extractHttpMethodsFromExpression(Expression expression) {
        if (expression.isArrayInitializerExpr()) {
            return expression.asArrayInitializerExpr().getValues().stream()
                    .map(this::expressionToRequestMethodName)
                    .flatMap(Optional::stream)
                    .map(this::toHttpMethod)
                    .flatMap(Optional::stream)
                    .toList();
        }

        return expressionToRequestMethodName(expression)
                .flatMap(this::toHttpMethod)
                .map(List::of)
                .orElse(List.of());
    }

    private Optional<String> expressionToRequestMethodName(Expression expression) {
        if (expression.isFieldAccessExpr()) {
            return Optional.of(expression.asFieldAccessExpr().getNameAsString());
        }
        if (expression.isNameExpr()) {
            return Optional.of(expression.asNameExpr().getNameAsString());
        }
        return Optional.empty();
    }

    private Optional<HttpMethod> toHttpMethod(String requestMethodName) {
        try {
            return Optional.of(HttpMethod.valueOf(requestMethodName.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<String> extractStringValueFromAnnotation(AnnotationExpr annotation) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            return extractFirstString(annotation.asSingleMemberAnnotationExpr().getMemberValue());
        }
        if (annotation.isNormalAnnotationExpr()) {
            NormalAnnotationExpr normalAnnotation = annotation.asNormalAnnotationExpr();
            Optional<String> value = extractNamedStringAttribute(normalAnnotation, "value");
            if (value.isPresent()) {
                return value;
            }
            return extractNamedStringAttribute(normalAnnotation, "path");
        }
        return Optional.empty();
    }

    private Optional<String> extractNamedStringAttribute(NormalAnnotationExpr annotation, String attributeName) {
        return annotation.getPairs().stream()
                .filter(pair -> pair.getNameAsString().equals(attributeName))
                .findFirst()
                .flatMap(pair -> extractFirstString(pair.getValue()));
    }

    private Optional<String> extractFirstString(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            return Optional.of(expression.asStringLiteralExpr().asString());
        }
        if (expression.isArrayInitializerExpr()) {
            ArrayInitializerExpr arrayInitializer = expression.asArrayInitializerExpr();
            return arrayInitializer.getValues().stream()
                    .filter(Expression::isStringLiteralExpr)
                    .map(value -> value.asStringLiteralExpr().asString())
                    .findFirst();
        }
        return Optional.empty();
    }

    private Optional<String> extractAnnotationName(AnnotationExpr annotation) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            return extractFirstString(annotation.asSingleMemberAnnotationExpr().getMemberValue());
        }
        if (!annotation.isNormalAnnotationExpr()) {
            return Optional.empty();
        }

        NormalAnnotationExpr normalAnnotation = annotation.asNormalAnnotationExpr();
        Optional<String> name = extractNamedStringAttribute(normalAnnotation, "name");
        if (name.isPresent()) {
            return name;
        }
        return extractNamedStringAttribute(normalAnnotation, "value");
    }

    private Optional<Boolean> extractRequiredFlag(AnnotationExpr annotation) {
        if (!annotation.isNormalAnnotationExpr()) {
            return Optional.empty();
        }

        return annotation.asNormalAnnotationExpr().getPairs().stream()
                .filter(pair -> pair.getNameAsString().equals("required"))
                .findFirst()
                .map(MemberValuePair::getValue)
                .filter(Expression::isBooleanLiteralExpr)
                .map(value -> value.asBooleanLiteralExpr().getValue());
    }

    private boolean isMappingAnnotation(AnnotationExpr annotation) {
        return annotationNameMatches(annotation, "GetMapping")
                || annotationNameMatches(annotation, "PostMapping")
                || annotationNameMatches(annotation, "PutMapping")
                || annotationNameMatches(annotation, "PatchMapping")
                || annotationNameMatches(annotation, "DeleteMapping")
                || annotationNameMatches(annotation, "RequestMapping");
    }

    private String combinePaths(String basePath, String methodPath) {
        return normalizePath((basePath == null ? "" : basePath) + "/" + (methodPath == null ? "" : methodPath));
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

    private String buildTagName(String controllerName) {
        if (controllerName != null && controllerName.endsWith("Controller")) {
            return controllerName.substring(0, controllerName.length() - "Controller".length());
        }
        return controllerName;
    }

    private boolean isAuthRequired(NodeWithAnnotations<?> classNode, NodeWithAnnotations<?> methodNode) {
        return hasAnnotation(classNode, "PreAuthorize")
                || hasAnnotation(classNode, "Secured")
                || hasAnnotation(classNode, "RolesAllowed")
                || hasAnnotation(methodNode, "PreAuthorize")
                || hasAnnotation(methodNode, "Secured")
                || hasAnnotation(methodNode, "RolesAllowed");
    }

    private boolean isDeprecated(NodeWithAnnotations<?> classNode, NodeWithAnnotations<?> methodNode) {
        return hasAnnotation(classNode, "Deprecated") || hasAnnotation(methodNode, "Deprecated");
    }

    private boolean hasAnnotation(NodeWithAnnotations<?> node, String annotationName) {
        return node.getAnnotations().stream().anyMatch(annotation -> annotationNameMatches(annotation, annotationName));
    }

    private boolean annotationNameMatches(AnnotationExpr annotation, String annotationName) {
        String currentName = annotation.getNameAsString();
        return currentName.equals(annotationName) || currentName.endsWith("." + annotationName);
    }

    private boolean isSimpleType(String typeName) {
        String cleanTypeName = cleanTypeName(typeName);
        return cleanTypeName.isBlank()
                || cleanTypeName.equals("String")
                || cleanTypeName.equals("Integer")
                || cleanTypeName.equals("int")
                || cleanTypeName.equals("Long")
                || cleanTypeName.equals("long")
                || cleanTypeName.equals("Boolean")
                || cleanTypeName.equals("boolean")
                || cleanTypeName.equals("Double")
                || cleanTypeName.equals("double")
                || cleanTypeName.equals("Float")
                || cleanTypeName.equals("float")
                || cleanTypeName.equals("BigDecimal")
                || cleanTypeName.equals("BigInteger")
                || cleanTypeName.equals("LocalDate")
                || cleanTypeName.equals("LocalDateTime")
                || cleanTypeName.equals("Date")
                || cleanTypeName.equals("UUID")
                || cleanTypeName.equals("byte[]")
                || cleanTypeName.equals("void")
                || cleanTypeName.equals("Void");
    }

    private String unwrapResponseType(String returnType) {
        String typeName = returnType == null ? "" : returnType.trim();
        if (typeName.equals("void") || typeName.equals("Void")) {
            return typeName;
        }

        String previous;
        do {
            previous = typeName;
            typeName = unwrapGeneric(typeName, "ResponseEntity");
            typeName = unwrapGeneric(typeName, "HttpEntity");
            typeName = unwrapGeneric(typeName, "Optional");
            typeName = unwrapGeneric(typeName, "List");
            typeName = unwrapGeneric(typeName, "Collection");
            typeName = unwrapGeneric(typeName, "Set");
        } while (!typeName.equals(previous));

        return cleanTypeName(typeName);
    }

    private String cleanTypeName(String rawType) {
        if (rawType == null) {
            return "";
        }

        String typeName = rawType.trim();
        typeName = unwrapGeneric(typeName, "List");
        typeName = unwrapGeneric(typeName, "Collection");
        typeName = unwrapGeneric(typeName, "Set");
        typeName = unwrapGeneric(typeName, "Optional");

        int genericIndex = typeName.indexOf('<');
        if (genericIndex >= 0) {
            typeName = typeName.substring(0, genericIndex);
        }

        typeName = typeName.replace("[]", "[]").trim();
        int packageIndex = typeName.lastIndexOf('.');
        if (packageIndex >= 0 && packageIndex < typeName.length() - 1) {
            typeName = typeName.substring(packageIndex + 1);
        }
        return typeName;
    }

    private String unwrapGeneric(String typeName, String wrapperName) {
        String simpleWrapper = wrapperName + "<";
        String qualifiedWrapperSuffix = "." + wrapperName + "<";

        if (typeName.startsWith(simpleWrapper) && typeName.endsWith(">")) {
            return typeName.substring(simpleWrapper.length(), typeName.length() - 1).trim();
        }

        int qualifiedIndex = typeName.indexOf(qualifiedWrapperSuffix);
        if (qualifiedIndex >= 0 && typeName.endsWith(">")) {
            return typeName.substring(qualifiedIndex + qualifiedWrapperSuffix.length(), typeName.length() - 1).trim();
        }

        return typeName;
    }

    private ApiMetadataParseResultResponse buildResponse(UUID projectId, int totalControllerFiles, ParseCounters counters) {
        String summary = "Parsed API metadata successfully. Controllers parsed: "
                + counters.parsedControllerFiles + "/" + totalControllerFiles
                + ", endpoints: " + counters.totalEndpoints + ".";

        return ApiMetadataParseResultResponse.builder()
                .projectId(projectId)
                .totalControllerFiles(totalControllerFiles)
                .parsedControllerFiles(counters.parsedControllerFiles)
                .totalEndpoints(counters.totalEndpoints)
                .totalParameters(counters.totalParameters)
                .totalSchemas(counters.totalSchemas)
                .totalSchemaFields(counters.totalSchemaFields)
                .totalEndpointSchemaMaps(counters.totalEndpointSchemaMaps)
                .summary(summary)
                .build();
    }

    private static class ParseCounters {
        private int parsedControllerFiles;
        private int totalEndpoints;
        private int totalParameters;
        private int totalSchemas;
        private int totalSchemaFields;
        private int totalEndpointSchemaMaps;
    }
}
