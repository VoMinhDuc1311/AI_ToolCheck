package com.aitoolcheck.ai_toolcheck1_backend.service.schema;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/**
 * Startup compatibility migrator for the {@code source_file.file_type} column.
 *
 * <h3>Why this exists</h3>
 * <p>This project does not use Flyway. The {@code source_file} table was created by Hibernate
 * {@code ddl-auto: update} when the schema was first initialised. At that time,
 * Hibernate's {@code MySQLDialect} mapped {@code @Enumerated(EnumType.STRING)} to a MySQL
 * {@code ENUM} column containing only the Java enum values that existed at that point in time.
 *
 * <p>When Phase 0 added new values ({@code BUILD}, {@code APP_CONFIG}, {@code SCRIPT}) to
 * the Java {@code FileType} enum, those values were not present in the MySQL {@code ENUM}
 * definition on the production database. MySQL therefore raises
 * <em>"Data truncated for column 'file_type' at row 1"</em> on every INSERT that uses a
 * new value. Hibernate {@code ddl-auto: update} cannot fix this because it does not perform
 * {@code MODIFY COLUMN} operations.
 *
 * <h3>Datasource injection strategy</h3>
 * <p>This service intentionally injects {@link DataSource} (not {@link JdbcTemplate}) because:
 * <ul>
 *   <li>The project has two {@link JdbcTemplate} beans: the Spring Boot auto-configured
 *       {@code jdbcTemplate} (MySQL) and {@code vectorJdbcTemplate} (PostgreSQL/Neon pgvector
 *       from {@code VectorDataSourceConfig}).</li>
 *   <li>Injecting {@code JdbcTemplate} by type could be ambiguous in certain Spring DI
 *       resolution scenarios and caused the PostgreSQL bean to be injected on production,
 *       leading to {@code org.postgresql.util.PSQLException: function database() does not exist}.</li>
 *   <li>{@code VectorDataSourceConfig} deliberately does NOT register a {@link DataSource} bean
 *       (to avoid interfering with Spring Boot MySQL auto-configuration), so there is exactly
 *       one {@link DataSource} bean in the context — the primary MySQL datasource.</li>
 * </ul>
 * <p>A local {@link JdbcTemplate} is constructed from the injected {@link DataSource} inside
 * this service and is never exposed as a bean.
 *
 * <h3>What this does</h3>
 * <ol>
 *   <li>Validates the injected datasource is MySQL or MariaDB (fail-fast if not).</li>
 *   <li>Reads {@code information_schema.COLUMNS} for {@code source_file.file_type}
 *       using the JDBC catalog (current database name) — avoids the MySQL-only
 *       {@code DATABASE()} function in the SQL, making the query portable enough to
 *       be safely validated at the Java level before execution.</li>
 *   <li>If the column is an {@code enum} or a {@code varchar} shorter than 50 chars,
 *       it issues {@code ALTER TABLE source_file MODIFY COLUMN file_type VARCHAR(50) NOT NULL}.</li>
 *   <li>If the column is already {@code VARCHAR(50)} or wider, it does nothing (idempotent).</li>
 *   <li>If the table or column does not yet exist (fresh DB), it logs a warning and skips —
 *       Hibernate {@code ddl-auto} will create the column correctly via {@code @Column(length = 50)}.</li>
 * </ol>
 *
 * <h3>Safety constraints</h3>
 * <ul>
 *   <li>Only ever touches {@code source_file.file_type} — no other tables or columns.</li>
 *   <li>Does not drop tables, truncate data, or delete rows.</li>
 *   <li>Fails fast (throws) if the wrong datasource is injected or ALTER fails.</li>
 *   <li>Fully idempotent: safe to re-run on every restart.</li>
 *   <li>Controlled by {@code app.schema-compat.enabled} (default: {@code true}).</li>
 * </ul>
 *
 * <h3>Long-term path</h3>
 * When Flyway is eventually introduced this class can be removed and replaced by a proper
 * versioned migration. The SQL file {@code V11__Alter_source_file_file_type_to_varchar.sql}
 * already exists in the migration folder for that future transition.
 */
@Slf4j
@Service
@Order(1)   // Run before any other ApplicationRunner beans
public class SourceFileSchemaCompatibilityService implements ApplicationRunner {

    private static final String TABLE_NAME      = "source_file";
    private static final String COLUMN_NAME     = "file_type";
    private static final int    REQUIRED_LENGTH = 50;

    /**
     * Injected as {@link DataSource} (not {@link JdbcTemplate}) to avoid ambiguity
     * between the primary MySQL bean and the {@code vectorJdbcTemplate} PostgreSQL bean.
     * Spring Boot's DataSource auto-configuration is the only {@link DataSource} bean
     * because {@code VectorDataSourceConfig} intentionally avoids registering its
     * PostgreSQL datasource as a bean.
     */
    private final DataSource dataSource;

    @Value("${app.schema-compat.enabled:true}")
    private boolean enabled;

    public SourceFileSchemaCompatibilityService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("[SchemaCompat] Schema compatibility check is DISABLED (app.schema-compat.enabled=false). Skipping.");
            return;
        }

        log.info("[SchemaCompat] Starting source_file.file_type compatibility check...");

        // ---------------------------------------------------------------
        // 1. Validate datasource product — must be MySQL or MariaDB
        //    and resolve the current catalog (database name)
        // ---------------------------------------------------------------
        String catalog;
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            String productName = meta.getDatabaseProductName();
            log.info("[SchemaCompat] Using database product: {}", productName);

            if (productName == null
                    || (!productName.toLowerCase().contains("mysql")
                        && !productName.toLowerCase().contains("mariadb"))) {
                throw new IllegalStateException(
                        "[SchemaCompat] Schema compatibility migrator must run on the primary MySQL datasource, "
                                + "but the injected DataSource reports database product: '"
                                + productName + "'. "
                                + "This indicates a datasource misconfiguration. "
                                + "Check that no @Qualifier or @Primary annotation is routing the wrong DataSource "
                                + "into SourceFileSchemaCompatibilityService.");
            }

            catalog = connection.getCatalog();
            if (catalog == null || catalog.isBlank()) {
                throw new IllegalStateException(
                        "[SchemaCompat] Could not determine current database catalog from JDBC connection. "
                                + "Ensure the MySQL connection URL includes a database name (e.g. jdbc:mysql://host/ai_tool).");
            }
            log.info("[SchemaCompat] Current database catalog: '{}'", catalog);

        } catch (IllegalStateException ex) {
            throw ex;   // re-throw our own validation errors as-is
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "[SchemaCompat] Failed to obtain database metadata from primary DataSource: " + ex.getMessage(), ex);
        }

        // ---------------------------------------------------------------
        // 2. Query information_schema using catalog (not DATABASE() function)
        //    TABLE_SCHEMA = ? avoids MySQL-only DATABASE() and is safely
        //    validated only after confirming product = MySQL above.
        // ---------------------------------------------------------------
        List<Map<String, Object>> rows = queryColumnInfo(catalog);

        if (rows.isEmpty()) {
            // Table or column not yet created — Hibernate ddl-auto will create it correctly
            // because @Column(length = 50) is now set on the entity field. Skip safely.
            log.warn("[SchemaCompat] Column {}.{} not found in information_schema (catalog='{}')."
                    + " This is expected on a brand-new database where Hibernate has not yet"
                    + " created the schema. Skipping ALTER — Hibernate will create the column"
                    + " with the correct definition.", TABLE_NAME, COLUMN_NAME, catalog);
            return;
        }

        Map<String, Object> row = rows.get(0);
        String dataType = String.valueOf(row.get("DATA_TYPE")).toLowerCase();
        Object maxLengthObj = row.get("CHARACTER_MAXIMUM_LENGTH");
        long characterMaxLength = maxLengthObj != null ? Long.parseLong(maxLengthObj.toString()) : 0L;

        log.info("[SchemaCompat] Current {}.{} definition: DATA_TYPE='{}', CHARACTER_MAXIMUM_LENGTH={}",
                TABLE_NAME, COLUMN_NAME, dataType, characterMaxLength);

        // ---------------------------------------------------------------
        // 3. Decide whether migration is needed
        // ---------------------------------------------------------------
        boolean needsMigration;
        String reason;

        if ("enum".equals(dataType)) {
            needsMigration = true;
            reason = "column is MySQL ENUM — new FileType values (BUILD, APP_CONFIG, SCRIPT) are not in the ENUM definition";
        } else if ("varchar".equals(dataType) && characterMaxLength < REQUIRED_LENGTH) {
            needsMigration = true;
            reason = "column is VARCHAR(" + characterMaxLength + ") which is too short for all FileType values (need " + REQUIRED_LENGTH + ")";
        } else if ("varchar".equals(dataType) && characterMaxLength >= REQUIRED_LENGTH) {
            log.info("[SchemaCompat] Column {}.{} is already VARCHAR({}) — no migration needed. ✓",
                    TABLE_NAME, COLUMN_NAME, characterMaxLength);
            return;
        } else {
            // e.g. TEXT, LONGTEXT — wide enough; no-op
            log.info("[SchemaCompat] Column {}.{} has DATA_TYPE='{}' which is compatible — no migration needed. ✓",
                    TABLE_NAME, COLUMN_NAME, dataType);
            return;
        }

        if (!needsMigration) {
            return;
        }

        // ---------------------------------------------------------------
        // 4. Apply the ALTER
        // ---------------------------------------------------------------
        log.warn("[SchemaCompat] Migration required: {}. Applying ALTER TABLE...", reason);

        String alterSql = "ALTER TABLE " + TABLE_NAME
                + " MODIFY COLUMN " + COLUMN_NAME + " VARCHAR(" + REQUIRED_LENGTH + ") NOT NULL";

        try {
            executeAlter(alterSql);
            log.info("[SchemaCompat] ✓ ALTER TABLE {} MODIFY COLUMN {} VARCHAR({}) NOT NULL — applied successfully.",
                    TABLE_NAME, COLUMN_NAME, REQUIRED_LENGTH);
        } catch (Exception ex) {
            // Fail fast: the application must not start with a broken schema because
            // every source upload will fail with "Data truncated" until this is fixed.
            log.error("[SchemaCompat] ✗ ALTER TABLE failed for {}.{}: {}",
                    TABLE_NAME, COLUMN_NAME, ex.getMessage(), ex);
            throw new IllegalStateException(
                    "[SchemaCompat] Failed to migrate " + TABLE_NAME + "." + COLUMN_NAME
                            + " to VARCHAR(" + REQUIRED_LENGTH + ") NOT NULL. "
                            + "The application cannot safely accept source uploads with the current schema. "
                            + "Cause: " + ex.getMessage(), ex);
        }
    }

    // -----------------------------------------------------------------------
    // Protected hooks — overrideable in tests to avoid full JDBC chain mocking
    // -----------------------------------------------------------------------

    /**
     * Queries {@code information_schema.COLUMNS} for the target column definition.
     * Protected so tests can override without mocking the full PreparedStatement chain.
     */
    protected List<Map<String, Object>> queryColumnInfo(String catalog) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String sql = """
                SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = ?
                  AND TABLE_NAME   = ?
                  AND COLUMN_NAME  = ?
                """;
        return jdbc.queryForList(sql, catalog, TABLE_NAME, COLUMN_NAME);
    }

    /**
     * Executes the ALTER TABLE statement.
     * Protected so tests can override to capture SQL without running real DDL.
     */
    protected void executeAlter(String sql) {
        new JdbcTemplate(dataSource).execute(sql);
    }
}
