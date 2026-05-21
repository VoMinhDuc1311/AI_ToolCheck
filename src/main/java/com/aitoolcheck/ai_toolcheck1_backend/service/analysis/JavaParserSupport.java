package com.aitoolcheck.ai_toolcheck1_backend.service.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

// Central parser helper — always uses Java 17 so records, sealed classes, and text blocks are supported.
public final class JavaParserSupport {

    private static final ParserConfiguration CONFIGURATION = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

    private JavaParserSupport() {}

    // Throws ParseProblemException on failure, same semantics as StaticJavaParser.parse().
    public static CompilationUnit parse(String source) {
        JavaParser parser = new JavaParser(CONFIGURATION);
        ParseResult<CompilationUnit> result = parser.parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            throw new com.github.javaparser.ParseProblemException(result.getProblems());
        }
        return result.getResult().get();
    }
}
