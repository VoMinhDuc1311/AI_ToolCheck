package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeMode;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.RuntimeType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceRuntimeTest {

    @Test
    void sourceRuntimeEntity_persistsBasicFields() {
        SourceRuntime runtime = SourceRuntime.builder()
                .runtimeMode(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE)
                .runtimeStatus(RuntimeStatus.BUILD_QUEUED)
                .runtimeType(RuntimeType.SPRING_BOOT_MAVEN)
                .publicBaseUrl("http://localhost:8080")
                .detectedPort(8080)
                .healthCheckPath("/actuator/health")
                .build();

        runtime.prePersist();

        assertThat(runtime.getRuntimeMode()).isEqualTo(RuntimeMode.AUTO_RUNTIME_FROM_SOURCE);
        assertThat(runtime.getRuntimeStatus()).isEqualTo(RuntimeStatus.BUILD_QUEUED);
        assertThat(runtime.getRuntimeType()).isEqualTo(RuntimeType.SPRING_BOOT_MAVEN);
        assertThat(runtime.getPublicBaseUrl()).isEqualTo("http://localhost:8080");
        assertThat(runtime.getDetectedPort()).isEqualTo(8080);
        assertThat(runtime.getCreatedAt()).isNotNull();
        assertThat(runtime.getUpdatedAt()).isNotNull();
    }
}
