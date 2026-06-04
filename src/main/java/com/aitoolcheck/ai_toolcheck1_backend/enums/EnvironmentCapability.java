package com.aitoolcheck.ai_toolcheck1_backend.enums;

/**
 * Capabilities that the host execution environment may or may not provide
 * for auto-starting source runtimes.
 *
 * <p>Each capability is independently probed at runtime by
 * {@link com.aitoolcheck.ai_toolcheck1_backend.service.runtime.EnvironmentCapabilityDetector}.
 */
public enum EnvironmentCapability {

    /**
     * The {@code docker} CLI binary is present on PATH inside the backend container.
     * Without this, Docker-based image builds and container starts are impossible.
     */
    DOCKER_CLI,

    /**
     * The Docker daemon socket ({@code /var/run/docker.sock} or equivalent) is
     * accessible from inside the backend container. Required for Docker API calls.
     */
    DOCKER_SOCKET,

    /**
     * A Java Development Kit (JDK) providing {@code javac} is available.
     * Required to compile source code before running as a JAR process.
     */
    JDK,

    /**
     * The Apache Maven build tool ({@code mvn}) is available on PATH.
     * Required to build Spring Boot Maven projects.
     */
    MAVEN,

    /**
     * The Gradle build tool ({@code gradle} or {@code gradlew}) is available.
     * Required to build Spring Boot Gradle projects.
     */
    GRADLE,

    /**
     * A writable temporary directory exists for materializing source files
     * before building. This is a baseline requirement for any autostart mode.
     */
    WRITABLE_TEMP_DIR
}
