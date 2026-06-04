package com.aitoolcheck.ai_toolcheck1_backend.service.schema;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
 * <h3>What this does</h3>
 * <ol>
 *   <li>Reads {@code information_schema.COLUMNS} for {@code source_file.file_type}.</li>
 *   <li>If the column is an {@code enum} or a {@code varchar} shorter than 50 chars,
 *       it issues {@code ALTER TABLE source_file MODIFY COLUMN file_type VARCHAR(50) NOT NULL}.</li>
 *   <li>If the column is already {@code VARCHAR(50)} or wider, it does nothing (idempotent).</li>
 *   <li>If the table or column does not yet exist (fresh DB, Hibernate has not run yet),
 *       it logs a warning and skips — Hibernate {@code ddl-auto} will create the column
 *       correctly based on the updated {@code @Column(length = 50)} annotation.</li>
 * </ol>
 *
 * <h3>Safety constraints</h3>
 * <ul>
 *   <li>Only ever touches {@code source_file.file_type} — no other tables or columns.</li>
 *   <li>Does not drop tables, truncate data, or delete rows.</li>
 *   <li>Fails fast (throws) if the ALTER itself fails, so the deployment health-check
 *       will catch the error instead of silently running with a broken schema.</li>
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
@Order(1)          // Run before any other ApplicationRunner beans
@RequiredArgsConstructor
public class SourceFileSchemaCompatibilityService implements ApplicationRunner {

    private static final String TABLE_NAME  = "source_file";
    private static final String COLUMN_NAME = "file_type";
    private static final int    REQUIRED_LENGTH = 50;

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.schema-compat.enabled:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("[SchemaCompat] Schema compatibility check is DISABLED (app.schema-compat.enabled=false). Skipping.");
            return;
        }

        log.info("[SchemaCompat] Starting source_file.file_type compatibility check...");

        // ---------------------------------------------------------------
        // 1. Query information_schema to find current column definition
        // ---------------------------------------------------------------
        String sql = """
                SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME   = ?
                  AND COLUMN_NAME  = ?
                """;

        var rows = jdbcTemplate.queryForList(sql, TABLE_NAME, COLUMN_NAME);

        if (rows.isEmpty()) {
            // Table or column not yet created — Hibernate ddl-auto will create it correctly
            // because @Column(length = 50) is now set on the entity field. Skip safely.
            log.warn("[SchemaCompat] Column {}.{} not found in information_schema. "
                    + "This is expected on a brand-new database where Hibernate has not yet "
                    + "created the schema. Skipping ALTER — Hibernate will create the column "
                    + "with the correct definition.", TABLE_NAME, COLUMN_NAME);
            return;
        }

        var row = rows.get(0);
        String dataType = String.valueOf(row.get("DATA_TYPE")).toLowerCase();
        Object maxLengthObj = row.get("CHARACTER_MAXIMUM_LENGTH");
        long characterMaxLength = maxLengthObj != null ? Long.parseLong(maxLengthObj.toString()) : 0L;

        log.info("[SchemaCompat] Current {}.{} definition: DATA_TYPE='{}', CHARACTER_MAXIMUM_LENGTH={}",
                TABLE_NAME, COLUMN_NAME, dataType, characterMaxLength);

        // ---------------------------------------------------------------
        // 2. Decide whether migration is needed
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

        // ---------------------------------------------------------------
        // 3. Apply the ALTER
        // ---------------------------------------------------------------
        log.warn("[SchemaCompat] Migration required: {}. Applying ALTER TABLE...", reason);

        String alterSql = "ALTER TABLE " + TABLE_NAME
                + " MODIFY COLUMN " + COLUMN_NAME + " VARCHAR(" + REQUIRED_LENGTH + ") NOT NULL";

        try {
            jdbcTemplate.execute(alterSql);
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
}
