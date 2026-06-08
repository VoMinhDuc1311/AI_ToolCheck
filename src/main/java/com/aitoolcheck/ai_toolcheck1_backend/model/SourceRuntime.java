package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BuildStrategy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.DockerfileSource;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "source_runtime",
        indexes = {
                @Index(name = "idx_source_runtime_project_id", columnList = "project_id"),
                @Index(name = "idx_source_runtime_source_version_id", columnList = "source_version_id"),
                @Index(name = "idx_source_runtime_runtime_status", columnList = "runtime_status"),
                @Index(name = "idx_source_runtime_project_source_version", columnList = "project_id,source_version_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceRuntime {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id", nullable = false)
    private SourceProject sourceProject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_version_id", referencedColumnName = "id")
    private SourceUploadVersion sourceVersion;

    @Column(name = "runtime_mode", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private RuntimeMode runtimeMode;

    @Column(name = "runtime_status", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private RuntimeStatus runtimeStatus;

    @Column(name = "runtime_type", length = 50)
    @Enumerated(EnumType.STRING)
    private RuntimeType runtimeType;

    @Column(name = "container_name")
    private String containerName;

    @Column(name = "image_name")
    private String imageName;

    @Column(name = "docker_network")
    private String dockerNetwork;

    @Column(name = "internal_base_url", length = 500)
    private String internalBaseUrl;

    @Column(name = "public_base_url", length = 500)
    private String publicBaseUrl;

    @Column(name = "internal_port")
    private Integer internalPort;

    @Column(name = "detected_port")
    private Integer detectedPort;

    @Column(name = "context_path")
    private String contextPath;

    @Column(name = "health_check_path")
    private String healthCheckPath;

    @Column(name = "last_health_status", length = 100)
    private String lastHealthStatus;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** The strategy the caller requested. Null for runtimes created before this feature. */
    @Column(name = "build_strategy_requested", length = 50)
    @Enumerated(EnumType.STRING)
    private BuildStrategy buildStrategyRequested;

    /** The strategy that was actually executed (e.g. AUTO_WITH_FALLBACK may use GENERATED). */
    @Column(name = "build_strategy_used", length = 50)
    @Enumerated(EnumType.STRING)
    private BuildStrategy buildStrategyUsed;

    /** Whether the uploaded or generated Dockerfile was used. */
    @Column(name = "dockerfile_source", length = 20)
    @Enumerated(EnumType.STRING)
    private DockerfileSource dockerfileSource;

    /** Non-null when AUTO_WITH_FALLBACK fell back to the generated Dockerfile. */
    @Column(name = "fallback_reason", columnDefinition = "TEXT")
    private String fallbackReason;

    @Column(name = "build_started_at")
    private LocalDateTime buildStartedAt;

    @Column(name = "build_finished_at")
    private LocalDateTime buildFinishedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "stopped_at")
    private LocalDateTime stoppedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (runtimeMode == null) {
            runtimeMode = RuntimeMode.AUTO_RUNTIME_FROM_SOURCE;
        }
        if (runtimeStatus == null) {
            runtimeStatus = RuntimeStatus.NOT_CREATED;
        }
        if (runtimeType == null) {
            runtimeType = RuntimeType.UNKNOWN;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
