package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.req.StartRuntimeRequest;
import com.aitoolcheck.ai_toolcheck1_backend.enums.BuildStrategy;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for SourceRuntimeController.resolveStrategy() — backward compat and validation.
 */
class SourceRuntimeControllerResolveStrategyTest {

    private final SourceRuntimeController controller =
            new SourceRuntimeController(null); // service not needed for unit tests

    private BuildStrategy resolve(String queryParam, StartRuntimeRequest body) {
        return controller.resolveStrategy(queryParam, body);
    }

    @Test
    void startRuntime_withoutBody_defaultsToAuto() {
        assertThat(resolve(null, null)).isEqualTo(BuildStrategy.AUTO);
    }

    @Test
    void startRuntime_emptyBodyNoStrategy_defaultsToAuto() {
        assertThat(resolve(null, new StartRuntimeRequest())).isEqualTo(BuildStrategy.AUTO);
    }

    @Test
    void startRuntime_bodyWithStrategy_usesBodyStrategy() {
        StartRuntimeRequest req = new StartRuntimeRequest(BuildStrategy.GENERATED_DOCKERFILE);
        assertThat(resolve(null, req)).isEqualTo(BuildStrategy.GENERATED_DOCKERFILE);
    }

    @Test
    void startRuntime_queryParamTakesPrecedenceOverBody() {
        StartRuntimeRequest req = new StartRuntimeRequest(BuildStrategy.GENERATED_DOCKERFILE);
        assertThat(resolve("AUTO_WITH_FALLBACK", req)).isEqualTo(BuildStrategy.AUTO_WITH_FALLBACK);
    }

    @Test
    void startRuntime_queryParamCaseInsensitive() {
        assertThat(resolve("generated_dockerfile", null)).isEqualTo(BuildStrategy.GENERATED_DOCKERFILE);
        assertThat(resolve("auto", null)).isEqualTo(BuildStrategy.AUTO);
    }

    @Test
    void startRuntime_withInvalidBuildStrategy_throws400() {
        assertThatThrownBy(() -> resolve("INVALID_STRATEGY", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid buildStrategy")
                .hasMessageContaining("INVALID_STRATEGY")
                .hasMessageContaining("AUTO");
    }

    @Test
    void startRuntime_allValidStrategiesAccepted() {
        for (BuildStrategy s : BuildStrategy.values()) {
            assertThat(resolve(s.name(), null)).isEqualTo(s);
        }
    }
}
