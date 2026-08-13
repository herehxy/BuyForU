package com.buyforu.commerce.it;

import com.buyforu.commerce.application.DomainEventPublisher;
import com.buyforu.commerce.infrastructure.OutboxDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 投递 Webhook 时不能还握着数据库事务。 */
@Testcontainers(disabledWithoutDocker = true)
class OutboxDispatchIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresSupport.postgres();

    private JdbcTemplate jdbc;
    private OutboxDispatcher dispatcher;
    private final AtomicBoolean publishedInsideTransaction = new AtomicBoolean(true);

    @BeforeEach
    void setUp() {
        var dataSource = PostgresSupport.dataSource(POSTGRES, "commerce_schema");
        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        DomainEventPublisher publisher = event ->
                publishedInsideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
        dispatcher = new OutboxDispatcher(jdbc, transactions, publisher, "it");
    }

    @Test
    void publishHappensAfterClaimTransactionCommits() {
        jdbc.update("""
                INSERT INTO commerce_schema.outbox_event
                    (event_id, aggregate_type, aggregate_id, event_type, payload, status)
                VALUES ('e-1', 'ORDER', 'o-1', 'ORDER_CREATED', '{}'::jsonb, 'PENDING')
                """);

        dispatcher.dispatch();

        assertFalse(publishedInsideTransaction.get());
        assertEquals("PUBLISHED", jdbc.queryForObject(
                "SELECT status FROM commerce_schema.outbox_event WHERE event_id='e-1'", String.class));
    }
}
