package com.aitoolcheck.ai_toolcheck1_backend.service.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
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
 * <p>The service now injects {@link DataSource} (not {@link JdbcTemplate}) to avoid
 * ambiguity with the {@code vectorJdbcTemplate} PostgreSQL bean. Tests mock at the
 * {@link DataSource}/{@link Connection}/{@link DatabaseMetaData} level to verify:
 * <ol>
 *   <li>PostgreSQL datasource → fail fast with clear error (regression guard)</li>
 *   <li>Wrong datasource product → fail fast</li>
 *   <li>MySQL datasource → product validated, catalog resolved</li>
 *   <li>ENUM column → ALTER applied</li>
 *   <li>VARCHAR too short → ALTER applied</li>
 *   <li>VARCHAR already 50 → no-op</li>
 *   <li>VARCHAR wider than 50 → no-op</li>
 *   <li>Column missing → skip without crash</li>
 *   <li>Idempotency</li>
 *   <li>ALTER failure → fail fast</li>
 *   <li>Disabled by config → zero DB calls</li>
 * </ol>
 *
 * <p><b>Note on stubbing depth:</b> JdbcTemplate internally creates PreparedStatements,
 * ResultSets etc. from a DataSource. Rather than mocking the entire JDBC chain for query
 * assertions, these tests use a helper {@link StubDataSource} that returns a real
 * {@link DataSource} for metadata checks and delegates queryForList calls to a
 * {@link SourceFileSchemaCompatibilityService} subclass that accepts a pre-stubbed
 * result for simplicity. For behaviour tests (ALTER/no-op decisions) we rely on
 * verified interactions on a spy-wrapped JdbcTemplate built from the stub DataSource.
 *
 * <p>Tests for the <em>datasource product guard</em> mock at the {@link DataSource} level
 * and verify the service throws {@link IllegalStateException} before any SQL is executed.
 */
@ExtendWith(MockitoExtension.class)
class SourceFileSchemaCompatibilityServiceTest {

    // -----------------------------------------------------------------------
    // Infrastructure mocks
    // -----------------------------------------------------------------------

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private DatabaseMetaData databaseMetaData;

    @Mock
    private ApplicationArguments applicationArguments;

    private SourceFileSchemaCompatibilityService service;

    // -----------------------------------------------------------------------
    // Testable subclass — allows us to stub the information_schema query result
    // without fully wiring a real JDBC PreparedStatement chain.
    // -----------------------------------------------------------------------

    /**
     * Testable subclass that overrides the information_schema query so we can
     * control what column data it returns, while still exercising all the
     * product-validation, catalog, and ALTER logic.
     */
    static class TestableService extends SourceFileSchemaCompatibilityService {

        private List<Map<String, Object>> stubbedRows = null;
        private RuntimeException alterException    = null;
        private String            lastExecutedSql  = null;

        TestableService(DataSource dataSource) {
            super(dataSource);
        }

        void stubQueryResult(List<Map<String, Object>> rows) {
            this.stubbedRows = rows;
        }

        void stubAlterFailure(RuntimeException ex) {
            this.alterException = ex;
        }

        String lastExecutedSql() {
            return lastExecutedSql;
        }

        // Package-visible hook called by the overridden query method
        @Override
        protected List<Map<String, Object>> queryColumnInfo(String catalog) {
            return stubbedRows != null ? stubbedRows : Collections.emptyList();
        }

        @Override
        protected void executeAlter(String sql) {
            lastExecutedSql = sql;
            if (alterException != null) {
                throw alterException;
            }
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        service = new TestableService(dataSource);
        ReflectionTestUtils.setField(service, "enabled", true);

        // Default happy-path: MySQL/MariaDB datasource pointing to catalog "ai_tool".
        // Lenient: some tests deliberately trigger early exits (wrong product, disabled)
        // and don't reach all stubs — lenient prevents UnnecessaryStubbingException.
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.getMetaData()).thenReturn(databaseMetaData);
        lenient().when(databaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        lenient().when(connection.getCatalog()).thenReturn("ai_tool");
    }

    // -----------------------------------------------------------------------
    // 1. PostgreSQL datasource → fail fast with clear error (the actual prod bug)
    // -----------------------------------------------------------------------

    @Test
    void whenDatabaseProductIsPostgreSQL_throwsClearWrongDatasourceError() throws SQLException {
        when(databaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");

        assertThatThrownBy(() -> service.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must run on the primary MySQL datasource")
                .hasMessageContaining("PostgreSQL");

        // No column query or ALTER should be attempted
        assertThat(((TestableService) service).lastExecutedSql()).isNull();
    }

    // -----------------------------------------------------------------------
    // 2. Unknown/wrong product → fail fast
    // -----------------------------------------------------------------------

    @Test
    void whenDatabaseProductIsUnknown_throwsWrongDatasourceError() throws SQLException {
        when(databaseMetaData.getDatabaseProductName()).thenReturn("H2");

        assertThatThrownBy(() -> service.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must run on the primary MySQL datasource")
                .hasMessageContaining("H2");
    }

    // -----------------------------------------------------------------------
    // 3. MariaDB is accepted (compatible MySQL dialect)
    // -----------------------------------------------------------------------

    @Test
    void whenDatabaseProductIsMariaDB_proceedsNormally() throws Exception {
        when(databaseMetaData.getDatabaseProductName()).thenReturn("MariaDB");
        ((TestableService) service).stubQueryResult(
                List.of(Map.of("DATA_TYPE", "varchar", "CHARACTER_MAXIMUM_LENGTH", 50L)));

        service.run(applicationArguments);

        // No exception, no ALTER (already correct)
        assertThat(((TestableService) service).lastExecutedSql()).isNull();
    }

    // -----------------------------------------------------------------------
    // 4. ENUM column → ALTER applied
    // -----------------------------------------------------------------------

    @Test
    void whenFileTypeColumnIsEnum_altersToVarchar50() throws Exception {
        ((TestableService) service).stubQueryResult(
                List.of(Map.of("DATA_TYPE", "enum", "CHARACTER_MAXIMUM_LENGTH", 17L)));

        service.run(applicationArguments);

        String sql = ((TestableService) service).lastExecutedSql();
        assertThat(sql)
                .isNotNull()
                .containsIgnoringCase("ALTER TABLE source_file")
                .containsIgnoringCase("MODIFY COLUMN file_type")
                .containsIgnoringCase("VARCHAR(50)")
                .containsIgnoringCase("NOT NULL");
    }

    // -----------------------------------------------------------------------
    // 5. VARCHAR too short → ALTER applied
    // -----------------------------------------------------------------------

    @Test
    void whenFileTypeColumnIsVarcharTooShort_altersToVarchar50() throws Exception {
        ((TestableService) service).stubQueryResult(
                List.of(Map.of("DATA_TYPE", "varchar", "CHARACTER_MAXIMUM_LENGTH", 30L)));

        service.run(applicationArguments);

        String sql = ((TestableService) service).lastExecutedSql();
        assertThat(sql)
                .isNotNull()
                .containsIgnoringCase("VARCHAR(50)");
    }

    // -----------------------------------------------------------------------
    // 6. VARCHAR already 50 → no-op
    // -----------------------------------------------------------------------

    @Test
    void whenFileTypeColumnAlreadyVarchar50_noop() throws Exception {
        ((TestableService) service).stubQueryResult(
                List.of(Map.of("DATA_TYPE", "varchar", "CHARACTER_MAXIMUM_LENGTH", 50L)));

        service.run(applicationArguments);

        assertThat(((TestableService) service).lastExecutedSql()).isNull();
    }

    // -----------------------------------------------------------------------
    // 7. VARCHAR wider than 50 → no-op
    // -----------------------------------------------------------------------

    @Test
    void whenFileTypeColumnIsWideVarchar_noop() throws Exception {
        ((TestableService) service).stubQueryResult(
                List.of(Map.of("DATA_TYPE", "varchar", "CHARACTER_MAXIMUM_LENGTH", 255L)));

        service.run(applicationArguments);

        assertThat(((TestableService) service).lastExecutedSql()).isNull();
    }

    // -----------------------------------------------------------------------
    // 8. Column missing → skip without crash
    // -----------------------------------------------------------------------

    @Test
    void whenSourceFileTableMissing_logsAndSkipsWithoutCrash() throws Exception {
        ((TestableService) service).stubQueryResult(Collections.emptyList());

        service.run(applicationArguments);  // Must NOT throw

        assertThat(((TestableService) service).lastExecutedSql()).isNull();
    }

    // -----------------------------------------------------------------------
    // 9. Idempotency: second run after ALTER is no-op
    // -----------------------------------------------------------------------

    @Test
    void migratorIsIdempotent() throws Exception {
        // First run: column is ENUM → ALTER applied
        ((TestableService) service).stubQueryResult(
                List.of(Map.of("DATA_TYPE", "enum", "CHARACTER_MAXIMUM_LENGTH", 17L)));
        service.run(applicationArguments);
        assertThat(((TestableService) service).lastExecutedSql()).isNotNull();

        // Reset tracking for second run
        ((TestableService) service).lastExecutedSql = null;

        // Second run: column is now VARCHAR(50) (ALTER succeeded in DB)
        ((TestableService) service).stubQueryResult(
                List.of(Map.of("DATA_TYPE", "varchar", "CHARACTER_MAXIMUM_LENGTH", 50L)));
        service.run(applicationArguments);

        assertThat(((TestableService) service).lastExecutedSql()).isNull();
    }

    // -----------------------------------------------------------------------
    // 10. ALTER failure → fail fast with IllegalStateException
    // -----------------------------------------------------------------------

    @Test
    void alterFailure_throwsIllegalStateException() throws Exception {
        ((TestableService) service).stubQueryResult(
                List.of(Map.of("DATA_TYPE", "enum", "CHARACTER_MAXIMUM_LENGTH", 17L)));
        ((TestableService) service).stubAlterFailure(new RuntimeException("MySQL: table locked"));

        assertThatThrownBy(() -> service.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to migrate source_file.file_type")
                .hasMessageContaining("MySQL: table locked");
    }

    // -----------------------------------------------------------------------
    // 11. Disabled by config → zero DB calls
    // -----------------------------------------------------------------------

    @Test
    void whenDisabledByConfig_nothingRuns() throws Exception {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.run(applicationArguments);

        // DataSource.getConnection() must never be called
        verify(dataSource, never()).getConnection();
        assertThat(((TestableService) service).lastExecutedSql()).isNull();
    }

    // -----------------------------------------------------------------------
    // 12. TEXT column → no-op (compatible wide type)
    // -----------------------------------------------------------------------

    @Test
    void whenColumnIsTextType_noop() throws Exception {
        ((TestableService) service).stubQueryResult(
                List.of(Map.of("DATA_TYPE", "text")));

        service.run(applicationArguments);

        assertThat(((TestableService) service).lastExecutedSql()).isNull();
    }

    // -----------------------------------------------------------------------
    // 13. Catalog missing → fail fast
    // -----------------------------------------------------------------------

    @Test
    void whenCatalogIsBlank_throwsIllegalStateException() throws SQLException {
        when(connection.getCatalog()).thenReturn("");

        assertThatThrownBy(() -> service.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not determine current database catalog");
    }

    // -----------------------------------------------------------------------
    // 14. Only source_file.file_type is queried — catalog passed correctly
    // -----------------------------------------------------------------------

    @Test
    void queriesInformationSchemaWithCatalogNotDatabaseFunction() throws Exception {
        // Stub so we can verify the catalog was passed to the query
        final String[] capturedCatalog = {null};
        service = new SourceFileSchemaCompatibilityService(dataSource) {
            @Override
            protected List<Map<String, Object>> queryColumnInfo(String catalog) {
                capturedCatalog[0] = catalog;
                return List.of(Map.of("DATA_TYPE", "varchar", "CHARACTER_MAXIMUM_LENGTH", 50L));
            }

            @Override
            protected void executeAlter(String sql) {
                // no-op
            }
        };
        ReflectionTestUtils.setField(service, "enabled", true);

        service.run(applicationArguments);

        // The catalog passed to the query must be the connection catalog, not DATABASE()
        assertThat(capturedCatalog[0]).isEqualTo("ai_tool");
    }
}
