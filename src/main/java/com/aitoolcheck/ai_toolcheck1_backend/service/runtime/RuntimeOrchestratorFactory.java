package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.res.EnvironmentCapabilityReport;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceRuntimeRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuntimeSourceMaterializer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Selects the appropriate {@link RuntimeOrchestratorStrategy} based on the
 * capabilities detected in the host environment at application startup.
 *
 * <h3>Selection priority:</h3>
 * <ol>
 *   <li>{@link DockerRuntimeOrchestrator} — when Docker CLI + Docker socket are both available.</li>
 *   <li>{@link UnsupportedRuntimeOrchestrator} — when no viable build path exists.</li>
 * </ol>
 *
 * <p>The capability probe runs once at startup (via {@link PostConstruct}) and the
 * chosen strategy is cached for the lifetime of the application context.
 * Call {@link #reprobeAndSelect()} to force a re-probe (e.g. from an admin endpoint).
 *
 * <h3>Current expected result on EC2 (t3.micro, eclipse-temurin:21-jre):</h3>
 * <ul>
 *   <li>DOCKER_SOCKET: missing (not mounted in docker-compose.prod.yml)</li>
 *   <li>DOCKER_CLI: missing (not installed in JRE image)</li>
 *   <li>JDK: missing (JRE only)</li>
 *   <li>MAVEN: missing</li>
 *   <li>GRADLE: missing</li>
 *   <li>→ selected: {@link UnsupportedRuntimeOrchestrator}</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RuntimeOrchestratorFactory {

    private final EnvironmentCapabilityDetector capabilityDetector;
    private final SourceRuntimeRepository sourceRuntimeRepository;
    private final ApiEndpointRepository apiEndpointRepository;
    private final RuntimeSourceMaterializer runtimeSourceMaterializer;
    private final RuntimeAutoProperties runtimeAutoProperties;

    private volatile RuntimeOrchestratorStrategy activeStrategy;
    private volatile EnvironmentCapabilityReport lastReport;

    @PostConstruct
    public void init() {
        try {
            reprobeAndSelect();
        } catch (Exception e) {
            log.error("[RuntimeOrchestratorFactory] Capability probe failed at startup, defaulting to UnsupportedRuntimeOrchestrator: {}", e.getMessage());
            activeStrategy = new UnsupportedRuntimeOrchestrator(
                    sourceRuntimeRepository,
                    "Startup capability probe failed: " + e.getMessage()
            );
        }
    }

    /**
     * Re-probes the environment and selects the best available strategy.
     * Thread-safe: updates are written to volatile fields atomically.
     *
     * @return the fresh {@link EnvironmentCapabilityReport} from the probe.
     */
    public EnvironmentCapabilityReport reprobeAndSelect() {
        log.info("[RuntimeOrchestratorFactory] Probing execution environment capabilities...");
        EnvironmentCapabilityReport report = capabilityDetector.detect();
        this.lastReport = report;

        RuntimeOrchestratorStrategy selected = select(report);
        this.activeStrategy = selected;

        log.info("[RuntimeOrchestratorFactory] Selected strategy: {} | canAutoStart={} | summary={}",
                selected.name(), report.canAutoStart(), report.getSummary());
        return report;
    }

    /**
     * Returns the currently active orchestrator strategy.
     * Guaranteed to be non-null after {@link PostConstruct} completes.
     */
    public RuntimeOrchestratorStrategy getStrategy() {
        return activeStrategy;
    }

    /**
     * Returns the last capability report produced by a probe.
     * May be null briefly during startup before {@link PostConstruct} completes.
     */
    public EnvironmentCapabilityReport getLastReport() {
        return lastReport;
    }

    // ── Selection logic ───────────────────────────────────────────────────────

    private RuntimeOrchestratorStrategy select(EnvironmentCapabilityReport report) {
        if (report.canRunDocker()) {
            log.info("[RuntimeOrchestratorFactory] Docker available → selecting DockerRuntimeOrchestrator");
            return new DockerRuntimeOrchestrator(
                    sourceRuntimeRepository,
                    apiEndpointRepository,
                    runtimeSourceMaterializer,
                    runtimeAutoProperties
            );
        }

        // Future: could add a JarProcessRuntimeOrchestrator for JDK+Maven/Gradle environments
        // if (report.canBuildMaven() || report.canBuildGradle()) { ... }

        log.warn("[RuntimeOrchestratorFactory] No viable build path found → selecting UnsupportedRuntimeOrchestrator");
        return new UnsupportedRuntimeOrchestrator(
                sourceRuntimeRepository,
                report.getSummary()
        );
    }
}
