package com.buyforu.commerce.infrastructure;

import com.buyforu.commerce.application.DomainEventPublisher;
import com.buyforu.commerce.application.DomainEventPublisher.DomainEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.time.Duration;

/**
 * 事务 Outbox 的异步投递器。
 * SKIP LOCKED 允许多实例并发消费，指数退避后仍失败的事件进入 FAILED 等待人工处理。
 */
@Component
public class OutboxDispatcher {
    private static final int MAX_ATTEMPTS = 10;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DomainEventPublisher publisher;

    public OutboxDispatcher(JdbcTemplate jdbc, TransactionTemplate transactions, DomainEventPublisher publisher) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${buyforu.outbox.poll-delay:PT1S}")
    public void dispatch() {
        while (Boolean.TRUE.equals(transactions.execute(status -> publishOne()))) {
            // Drain committed events. Each iteration holds its own short row-lock transaction.
        }
    }

    private boolean publishOne() {
        List<OutboxRow> events = jdbc.query("""
                SELECT event_id, aggregate_type, aggregate_id, event_type, payload::text
                     , attempts
                FROM commerce_schema.outbox_event
                WHERE status = 'PENDING' AND next_attempt_at <= now() ORDER BY created_at
                FOR UPDATE SKIP LOCKED LIMIT 1
                """, (result, row) -> new OutboxRow(new DomainEvent(result.getString(1), result.getString(2),
                result.getString(3), result.getString(4), result.getString(5)), result.getInt(6)));
        if (events.isEmpty()) return false;
        OutboxRow row = events.getFirst();
        DomainEvent event = row.event();
        try {
            publisher.publish(event);
            jdbc.update("""
                    UPDATE commerce_schema.outbox_event
                    SET status = 'PUBLISHED', published_at = now(), last_error = NULL
                    WHERE event_id = ? AND status = 'PENDING'
                    """, event.eventId());
        } catch (RuntimeException failure) {
            int attempts = row.attempts() + 1;
            Duration retryAfter = Duration.ofSeconds(Math.min(300L, 1L << Math.min(attempts, 8)));
            jdbc.update("""
                    UPDATE commerce_schema.outbox_event
                    SET attempts = ?,
                        status = CASE WHEN ? >= ? THEN 'FAILED' ELSE 'PENDING' END,
                        next_attempt_at = now() + (? * interval '1 second'), last_error = ?
                    WHERE event_id = ? AND status = 'PENDING'
                    """, attempts, attempts, MAX_ATTEMPTS, retryAfter.toSeconds(),
                    truncate(failure.getMessage()), event.eventId());
            return false;
        }
        return true;
    }

    private static String truncate(String value) {
        if (value == null) return "publisher failure without message";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private record OutboxRow(DomainEvent event, int attempts) { }
}
