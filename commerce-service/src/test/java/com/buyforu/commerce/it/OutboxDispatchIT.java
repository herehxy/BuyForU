package com.buyforu.commerce.it;

import com.buyforu.commerce.application.DomainEventPublisher;
import com.buyforu.commerce.infrastructure.OutboxDispatcher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

/** 投递 Webhook 时不能还握着数据库事务；单周期投递量必须有上限。 */
@Testcontainers(disabledWithoutDocker = true)
class OutboxDispatchIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresSupport.postgres();

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private final AtomicBoolean publishedInsideTransaction = new AtomicBoolean(true);

    @BeforeEach
    void setUp() {
        var dataSource = PostgresSupport.dataSource(POSTGRES, "commerce_schema");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private OutboxDispatcher dispatcher(int maxPerCycle) {
        return dispatcher(maxPerCycle, new SimpleMeterRegistry());
    }

    private OutboxDispatcher dispatcher(int maxPerCycle, SimpleMeterRegistry meters) {
        DomainEventPublisher publisher = event ->
                publishedInsideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
        return new OutboxDispatcher(jdbc, transactions, publisher, "it", maxPerCycle, meters);
    }

    @Test
    void publishHappensAfterClaimTransactionCommits() {
        insertEvent("e-1");
        dispatcher(100).dispatch();

        assertFalse(publishedInsideTransaction.get());
        assertEquals("PUBLISHED", statusOf("e-1"));
    }

    /**
     * 单周期上限：积压再多，一轮也只投这么多，剩下的留给下一个固定延迟周期。
     * 没有上限时一次大积压会让循环长时间占着调度线程，停机也无法及时收尾。
     */
    @Test
    void stopsDrainingOnceThePerCycleCapIsReached() {
        // 先把其他用例可能留下的待投递事件排空，保证队列里接下来只有这三条。
        dispatcher(10_000).dispatch();
        insertEvent("c-1");
        insertEvent("c-2");
        insertEvent("c-3");

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        OutboxDispatcher capped = dispatcher(2, meters);
        capped.dispatch();

        assertEquals("PUBLISHED", statusOf("c-1"));
        assertEquals("PUBLISHED", statusOf("c-2"));
        assertEquals("PENDING", statusOf("c-3"), "触顶后必须留到下一个周期，不能在一轮里投完");
        assertEquals(1.0, meters.counter("buyforu_outbox_cycle_capped_total").count(),
                "触顶必须计数：持续增长就说明积压追不上来");

        // 下一个周期接着投完，说明只是延后而不是丢弃。
        capped.dispatch();
        assertEquals("PUBLISHED", statusOf("c-3"));
    }

    private void insertEvent(String eventId) {
        jdbc.update("""
                INSERT INTO commerce_schema.outbox_event
                    (event_id, aggregate_type, aggregate_id, event_type, payload, status)
                VALUES (?, 'ORDER', 'o-1', 'ORDER_CREATED', '{}'::jsonb, 'PENDING')
                """, eventId);
    }

    private String statusOf(String eventId) {
        return jdbc.queryForObject(
                "SELECT status FROM commerce_schema.outbox_event WHERE event_id=?", String.class, eventId);
    }
}
