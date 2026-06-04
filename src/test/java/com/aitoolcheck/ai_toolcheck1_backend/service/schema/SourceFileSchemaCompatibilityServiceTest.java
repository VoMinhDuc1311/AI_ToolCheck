package com.aitoolcheck.ai_toolcheck1_backend.service.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SourceFileSchemaCompatibilityService}.
 *
 * <p>All DB calls are mocked via {@link JdbcTemplate}. Tests cover:
 * <ol>
 *   <li>ENUM column → ALTER applied</li>
 *   <li>VARCHAR too short → ALTER applied</li>
 *   <li>VARCHAR already 50 → no-op</li>
 *   <li>VARCHAR wider than 50 → no-op</li>
 *   <li>Column missing (table not yet created) → skip without crash</li>
 *   <li>Idempotency: second run after ALTER is a no-op</li>
 *   <li>ALTER failure → fail fast with IllegalStateException</li>
 *   <li>Only source_file.file_type is queried/altered (no other tables touched)</li>
 *   <li>Disabled via config → completely skipped</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SourceFileSchemaCompatibilityServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ApplicationArguments applicationArguments;

    private SourceFileSchemaCompatibilityService service;

    @BeforeEach
    void setUp() {
        service = new SourceFileSchemaCompatibilityService(jdbcTemplate);
        // Default: enabled
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    // -----------------------------------------------------------------------
    // 1. ENUM column → ALTER applied
    // -----------------------------------------------------------------------

    @Test
    void whenFileTypeColumnIsEnum_altersToVarchar50() throws Exception {
        stubColumnInfo("enum", 17L);

        service.run(applicationArguments);

        verifyAlterExecuted();
    }

    // -----------------------------------------------------------------------
    // 2. VARCHAR too short → ALTER applied
    // -----------------------------------------------------------------------

    @Test
    void whenFileTypeColumnIsVarcharTooShort_altersToVarchar50() throws Exception {
        stubColumnInfo("varchar", 30L);

        service.run(applicationArguments);

        verifyAlterExecuted();
    }

    // -----------------------------------------------------------------------
    // 3. VARCHAR already 50 → no-op
    // -----------------------------------------------------------------------

    @Test
    void whenFileTypeColumnAlreadyVarchar50_noop() throws Exception {
        stubColumnInfo("varchar", 50L);

        service.run(applicationArguments);

        verifyAlterNeverExecuted();
    }

    // -----------------------------------------------------------------------
    // 4. VARCHAR wider than 50 → no-op
    // -----------------------------------------------------------------------

    @Test
    void whenFileTypeColumnIsWideVarchar_noop() throws Exception {
        stubColumnInfo("varchar", 255L);

        service.run(applicationArguments);

        verifyAlterNeverExecuted();
    }

    // -----------------------------------------------------------------------
    // 5. Table/column not yet created → log warning, skip, no crash
    // -----------------------------------------------------------------------

    @Test
    void whenSourceFileTableMissing_logsAndSkipsWithoutCrash() throws Exception {
        when(jdbcTemplate.queryForList(anyString(), eq("source_file"), eq("file_type")))
                .thenReturn(Collections.emptyList());

        // Must NOT throw
        service.run(applicationArguments);

        verifyAlterNeverExecuted();
    }

    // -----------------------------------------------------------------------
    // 6. Idempotency: second run after ALTER is no-op
    // -----------------------------------------------------------------------

    @Test
    void migratorIsIdempotent() throws Exception {
        // First run: column is ENUM → ALTER is applied
        stubColumnInfo("enum", 17L);
        service.run(applicationArguments);
        verifyAlterExecuted();

        // Reset mocks for second run
        reset(jdbcTemplate);

        // Second run: column is now VARCHAR(50) (as if ALTER succeeded in DB)
        stubColumnInfo("varchar", 50L);
        service.run(applicationArguments);

        verifyAlterNeverExecuted();
    }

    // -----------------------------------------------------------------------
    // 7. ALTER failure → fail fast with IllegalStateException
    // -----------------------------------------------------------------------

    @Test
    void alterFailure_throwsIllegalStateException() throws Exception {
        stubColumnInfo("enum", 17L);
        doThrow(new RuntimeException("MySQL: table locked"))
                .when(jdbcTemplate).execute(anyString());

        assertThatThrownBy(() -> service.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to migrate source_file.file_type")
                .hasMessageContaining("MySQL: table locked");
    }

    // -----------------------------------------------------------------------
    // 8. Only source_file.file_type is queried — no other tables touched
    // -----------------------------------------------------------------------

    @Test
    void doesNotTouchOtherTables() throws Exception {
        // Column already correct → no ALTER
        stubColumnInfo("varchar", 50L);

        service.run(applicationArguments);

        // Verify the information_schema query is scoped to source_file / file_type only.
        // The service calls queryForList(sql, "source_file", "file_type") via varargs.
        verify(jdbcTemplate).queryForList(anyString(), eq("source_file"), eq("file_type"));

        // No ALTER or any other DML
        verifyAlterNeverExecuted();
    }

    // -----------------------------------------------------------------------
    // 9. Disabled via config → completely skipped
    // -----------------------------------------------------------------------

    @Test
    void whenDisabledByConfig_nothingRuns() throws Exception {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.run(applicationArguments);

        // No DB calls at all
        verifyNoInteractions(jdbcTemplate);
    }

    // -----------------------------------------------------------------------
    // 10. TEXT column → no-op (wide enough)
    // -----------------------------------------------------------------------

    @Test
    void whenColumnIsTextType_noop() throws Exception {
        // Some wide type like TEXT that is not varchar/enum
        stubColumnInfo("text", null);

        service.run(applicationArguments);

        verifyAlterNeverExecuted();
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    private void stubColumnInfo(String dataType, Long characterMaxLength) {
        Map<String, Object> row = characterMaxLength != null
                ? Map.of("DATA_TYPE", dataType, "CHARACTER_MAXIMUM_LENGTH", characterMaxLength)
                : Map.of("DATA_TYPE", dataType);
        when(jdbcTemplate.queryForList(anyString(), eq("source_file"), eq("file_type")))
                .thenReturn(List.of(row));
    }

    private void verifyAlterExecuted() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(sqlCaptor.capture());
        String executedSql = sqlCaptor.getValue();
        assertThat(executedSql)
                .containsIgnoringCase("ALTER TABLE source_file")
                .containsIgnoringCase("MODIFY COLUMN file_type")
                .containsIgnoringCase("VARCHAR(50)")
                .containsIgnoringCase("NOT NULL");
    }

    private void verifyAlterNeverExecuted() {
        verify(jdbcTemplate, never()).execute(anyString());
    }
}
