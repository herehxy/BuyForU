package com.buyforu.agent.it;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * 集成测试共享的 PostgreSQL 容器与数据源。public 是因为并发包内的 IT
 * （如 CommandWorkerHeartbeatIT）需要访问包级私有的判定方法，因而必须留在被测类同包，
 * 无法放进 com.buyforu.agent.it。
 */
public final class PostgresSupport {
    private PostgresSupport() { }

    public static PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                .withDatabaseName("buyforu")
                .withUsername("buyforu")
                .withPassword("buyforu");
    }

    public static DataSource dataSource(PostgreSQLContainer<?> postgres) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(4);
        HikariDataSource dataSource = new HikariDataSource(config);
        Flyway.configure()
                .dataSource(dataSource)
                .schemas("agent_schema")
                .defaultSchema("agent_schema")
                // agent-app 的集成测试 classpath 还包含 commerce-service test fixture；
                // 使用模块自己的文件目录，避免两个模块都叫 V1 的迁移被 Flyway 误判为重复版本。
                .locations("filesystem:src/main/resources/db/migration")
                .load()
                .migrate();
        return dataSource;
    }
}
