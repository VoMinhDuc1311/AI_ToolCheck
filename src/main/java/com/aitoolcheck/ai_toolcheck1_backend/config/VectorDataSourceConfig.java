package com.aitoolcheck.ai_toolcheck1_backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Secondary DataSource configuration for pgvector (PostgreSQL).
 *
 * <p><b>CRITICAL DESIGN DECISION:</b> This config intentionally does NOT register
 * the PostgreSQL DataSource as a Spring Bean. If we registered it as a
 * {@code @Bean DataSource}, Spring Boot's {@code DataSourceAutoConfiguration}
 * would back off (due to {@code @ConditionalOnMissingBean(DataSource.class)}),
 * causing JPA/Hibernate to use the PostgreSQL datasource instead of MySQL,
 * which would crash on MySQL-specific types like {@code LONGTEXT}.
 *
 * <p>Instead, the DataSource is created inline inside {@link #vectorJdbcTemplate()}
 * and never exposed as a bean. This keeps Spring Boot's MySQL auto-configuration
 * intact while giving us a dedicated PostgreSQL {@link JdbcTemplate} for vector ops.
 */
@Slf4j
@Configuration
public class VectorDataSourceConfig {

    @Value("${vector.datasource.url}")
    private String url;

    @Value("${vector.datasource.username}")
    private String username;

    @Value("${vector.datasource.password}")
    private String password;

    @Value("${vector.datasource.driver-class-name}")
    private String driverClassName;

    /**
     * Creates a {@link JdbcTemplate} bean backed by a PostgreSQL connection pool
     * for pgvector similarity search.
     *
     * <p>The DataSource is intentionally created here and NOT exposed as a separate
     * {@code @Bean DataSource} to avoid interfering with Spring Boot's primary
     * MySQL DataSource auto-configuration.
     *
     * <p>Inject this bean by name {@code vectorJdbcTemplate} in RAG services.
     */
    @Bean(name = "vectorJdbcTemplate")
    public JdbcTemplate vectorJdbcTemplate() {
        log.info("[VectorDataSourceConfig] Initializing pgvector JdbcTemplate — URL: {}", url);

        DataSource vectorDataSource = DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .driverClassName(driverClassName)
                .build();

        return new JdbcTemplate(vectorDataSource);
    }
}
