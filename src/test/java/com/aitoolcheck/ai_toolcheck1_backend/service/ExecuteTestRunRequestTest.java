package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.testrun.req.ExecuteTestRunRequest;
import com.aitoolcheck.ai_toolcheck1_backend.enums.EnvironmentType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionMode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExecuteTestRunRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void whenValidRequestWithoutTestRunId_validationSucceeds() {
        ExecuteTestRunRequest request = ExecuteTestRunRequest.builder()
                .projectId(UUID.randomUUID())
                .baseUrl("http://localhost:8080")
                .testCaseIds(List.of(UUID.randomUUID()))
                .runName("E2E Test Run")
                .description("Execution test")
                .environmentName(EnvironmentType.DEV)
                .executionMode(ExecutionMode.READ_ONLY)
                .build();

        Set<ConstraintViolation<ExecuteTestRunRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void whenMissingProjectId_validationFails() {
        ExecuteTestRunRequest request = ExecuteTestRunRequest.builder()
                .baseUrl("http://localhost:8080")
                .testCaseIds(List.of(UUID.randomUUID()))
                .build();

        Set<ConstraintViolation<ExecuteTestRunRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("projectId is required");
    }

    @Test
    void whenMissingBaseUrl_validationFails() {
        ExecuteTestRunRequest request = ExecuteTestRunRequest.builder()
                .projectId(UUID.randomUUID())
                .testCaseIds(List.of(UUID.randomUUID()))
                .build();

        Set<ConstraintViolation<ExecuteTestRunRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("baseUrl is required");
    }

    @Test
    void whenTestCaseIdsEmpty_validationFails() {
        ExecuteTestRunRequest request = ExecuteTestRunRequest.builder()
                .projectId(UUID.randomUUID())
                .baseUrl("http://localhost:8080")
                .testCaseIds(List.of())
                .build();

        Set<ConstraintViolation<ExecuteTestRunRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("testCaseIds must not be empty");
    }
}
