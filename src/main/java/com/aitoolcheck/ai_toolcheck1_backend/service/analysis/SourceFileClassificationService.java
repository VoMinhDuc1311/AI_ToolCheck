package com.aitoolcheck.ai_toolcheck1_backend.service.analysis;

import com.aitoolcheck.ai_toolcheck1_backend.enums.FileType;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SourceFileClassificationService {

    public FileType detectFileType(
            SourceFile sourceFile,
            CompilationUnit compilationUnit,
            List<String> annotationNames
    ) {
        String path = normalizedPath(sourceFile);
        String name = normalizedName(sourceFile);
        List<ClassOrInterfaceDeclaration> classes = compilationUnit.findAll(ClassOrInterfaceDeclaration.class);

        if (hasAny(annotationNames, "SpringBootApplication")) {
            return FileType.APPLICATION;
        }
        if (hasAny(annotationNames, "RestController", "Controller", "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping")) {
            return FileType.CONTROLLER;
        }
        if (hasAny(annotationNames, "RestControllerAdvice", "ControllerAdvice", "ExceptionHandler")) {
            return FileType.EXCEPTION_HANDLER;
        }
        if (hasAny(annotationNames, "EnableWebSecurity", "PreAuthorize")
                || path.contains("/security/")
                || path.contains("/auth/")
                || name.contains("security")
                || name.contains("jwt")
                || name.contains("token")) {
            return FileType.SECURITY;
        }
        if (extendsOrImplements(classes, "OncePerRequestFilter") || extendsOrImplements(classes, "Filter")) {
            return FileType.FILTER;
        }
        if (extendsOrImplements(classes, "HandlerInterceptor")) {
            return FileType.INTERCEPTOR;
        }
        if (path.contains("/service/impl/") || name.endsWith("serviceimpl.java")) {
            return FileType.SERVICE_IMPL;
        }
        if (hasAny(annotationNames, "Service") || path.contains("/service/")) {
            return FileType.SERVICE;
        }
        if (hasAny(annotationNames, "Repository")
                || extendsOrImplements(classes, "JpaRepository")
                || extendsOrImplements(classes, "CrudRepository")
                || extendsOrImplements(classes, "PagingAndSortingRepository")
                || extendsOrImplements(classes, "MongoRepository")
                || path.contains("/repository/")
                || path.contains("/respository/")
                || name.contains("repository")) {
            return FileType.REPOSITORY;
        }
        if (hasAny(annotationNames, "Entity", "Table", "MappedSuperclass", "Embeddable")) {
            return FileType.ENTITY;
        }
        if (path.contains("/request/") || path.contains("/req/") || name.endsWith("request.java")) {
            return FileType.REQUEST;
        }
        if (path.contains("/response/") || path.contains("/res/") || name.endsWith("response.java")) {
            return FileType.RESPONSE;
        }
        if (path.contains("/dto/") || name.contains("dto")) {
            return FileType.DTO;
        }
        if (!compilationUnit.findAll(EnumDeclaration.class).isEmpty()) {
            return FileType.ENUM;
        }
        if (!compilationUnit.findAll(AnnotationDeclaration.class).isEmpty()) {
            return FileType.ANNOTATION;
        }
        if (isInterfaceOnly(classes)) {
            return FileType.INTERFACE;
        }
        if (hasAny(annotationNames, "Configuration", "Bean")) {
            return FileType.CONFIG;
        }
        if (extendsOrImplements(classes, "ConstraintValidator") || name.contains("validator")) {
            return FileType.VALIDATOR;
        }
        if (hasAny(annotationNames, "Mapper") || path.contains("/mapper/") || name.contains("mapper")) {
            return FileType.MAPPER;
        }
        if (extendsOrImplements(classes, "RuntimeException") || extendsOrImplements(classes, "Exception")) {
            return FileType.EXCEPTION;
        }
        if (hasAny(annotationNames, "Scheduled") || name.contains("scheduler") || name.contains("job") || name.contains("task")) {
            return FileType.SCHEDULER;
        }
        if (hasAny(annotationNames, "EventListener") || name.contains("listener")) {
            return FileType.LISTENER;
        }
        if (name.endsWith("event.java")) {
            return FileType.EVENT;
        }
        if (extendsOrImplements(classes, "CommandLineRunner") || extendsOrImplements(classes, "ApplicationRunner")) {
            return FileType.COMMAND;
        }
        if (name.contains("constant") || name.contains("constants") || name.contains("errorcode")) {
            return FileType.CONSTANT;
        }
        if (path.contains("/util/") || path.contains("/utils/") || name.contains("util") || name.contains("helper")) {
            return FileType.UTIL;
        }
        if (path.startsWith("src/test/")
                || path.contains("/src/test/")
                || name.endsWith("test.java")
                || name.endsWith("tests.java")
                || hasAny(annotationNames, "Test", "SpringBootTest", "WebMvcTest", "DataJpaTest")) {
            return FileType.TEST;
        }
        if (path.contains("/model/")) {
            return FileType.MODEL;
        }

        return FileType.UNKNOWN;
    }

    private boolean hasAny(List<String> annotationNames, String... candidates) {
        for (String candidate : candidates) {
            if (annotationNames.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean extendsOrImplements(List<ClassOrInterfaceDeclaration> classes, String typeName) {
        return classes.stream().anyMatch(declaration ->
                declaration.getExtendedTypes().stream().anyMatch(type -> type.getNameAsString().equals(typeName))
                        || declaration.getImplementedTypes().stream().anyMatch(type -> type.getNameAsString().equals(typeName))
        );
    }

    private boolean isInterfaceOnly(List<ClassOrInterfaceDeclaration> classes) {
        return !classes.isEmpty() && classes.stream().allMatch(ClassOrInterfaceDeclaration::isInterface);
    }

    private String normalizedPath(SourceFile sourceFile) {
        if (sourceFile.getFilePath() == null) {
            return "";
        }
        return sourceFile.getFilePath().replace("\\", "/").toLowerCase();
    }

    private String normalizedName(SourceFile sourceFile) {
        if (sourceFile.getFileName() == null) {
            return "";
        }
        return sourceFile.getFileName().toLowerCase();
    }
}
