package com.aitoolcheck.ai_toolcheck1_backend.service.runtime;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.RuntimeAutoProperties;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.SourceRuntimeRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.RuntimeSourceMaterializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RuntimeBeanWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RuntimeBeanWiringConfig.class)
            .withPropertyValues(
                    "ai.runtime.enabled=false",
                    "ai.runtime.docker.enabled=false"
            );

    @Test
    void applicationContextCreatesEnvironmentDetectorAndRuntimeFactory() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RuntimeAutoProperties.class);
            assertThat(context).hasSingleBean(EnvironmentCapabilityDetector.class);
            assertThat(context).hasSingleBean(RuntimeOrchestratorFactory.class);
            assertThat(context.getBean(RuntimeOrchestratorFactory.class).getStrategy())
                    .isInstanceOf(UnsupportedRuntimeOrchestrator.class);
        });
    }

    @Configuration
    @EnableConfigurationProperties(RuntimeAutoProperties.class)
    @Import({EnvironmentCapabilityDetector.class, RuntimeOrchestratorFactory.class})
    static class RuntimeBeanWiringConfig {
        @Bean
        SourceRuntimeRepository sourceRuntimeRepository() {
            return mock(SourceRuntimeRepository.class);
        }

        @Bean
        ApiEndpointRepository apiEndpointRepository() {
            return mock(ApiEndpointRepository.class);
        }

        @Bean
        RuntimeSourceMaterializer runtimeSourceMaterializer() {
            return mock(RuntimeSourceMaterializer.class);
        }
    }
}
