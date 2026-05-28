package com.aitoolcheck.ai_toolcheck1_backend.enums;

/**
 * Detected project build-system / framework type based on the presence
 * of well-known marker files in the root of a GitHub repository.
 *
 * <p>Detection precedence (first match wins):
 * <ol>
 *   <li>{@link #SPRING_BOOT}  — {@code pom.xml} + {@code src/main/java}
 *   <li>{@link #JAVA_MAVEN}   — {@code pom.xml} only
 *   <li>{@link #JAVA_GRADLE}  — {@code build.gradle} or {@code settings.gradle}
 *   <li>{@link #NODE}         — {@code package.json} only
 *   <li>{@link #UNKNOWN}      — none of the above recognised
 * </ol>
 */
public enum GitHubDetectedProjectType {
    SPRING_BOOT,
    JAVA_MAVEN,
    JAVA_GRADLE,
    NODE,
    UNKNOWN
}
