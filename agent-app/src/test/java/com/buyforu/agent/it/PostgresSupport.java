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
        return dataSource(postgres, 4);
    }

    /** 并发争用测试需要大于并发度的连接池，否则测到的是连接饥饿而不是租约竞争。 */
    public static DataSource dataSource(PostgreSQLContainer<?> postgres, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(poolSize);
        // 必须与生产的 spring.datasource.hikari.connection-init-sql 保持一致。
        // V1 迁移在 defaultSchema 下执行 `CREATE EXTENSION vector`，所以扩展落在 agent_schema；
        // 连接上不设 search_path 时，`CAST(? AS vector)` 会报 "type vector does not exist"，
        // 看起来像 SQL 写错了，实际是测试环境少复现了生产的一条连接前置条件。
        config.setConnectionInitSql("SET search_path TO agent_schema, public");
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
