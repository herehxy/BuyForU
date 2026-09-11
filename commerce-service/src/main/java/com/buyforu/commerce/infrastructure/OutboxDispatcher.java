package com.buyforu.commerce.infrastructure;

import com.buyforu.commerce.application.DomainEventPublisher;
import com.buyforu.commerce.application.DomainEventPublisher.DomainEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 先短事务把事件标成 CLAIMED，提交后再发 HTTP。
 * 这样 Webhook 卡住时不会握着 outbox 行锁和数据库连接。
 *
 * <p>投递与 CLAIMED 回收跑在两套独立调度器上：投递是"一次跑完整个积压才返回"的循环，
 * 回收只是两条 UPDATE，让它们共用单线程会让回收被积压饿死。</p>
 */
@Component
public class OutboxDispatcher {
    private static final int MAX_ATTEMPTS = 10;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DomainEventPublisher publisher;
    private final String instanceId;
    private final int maxPerCycle;
    private final MeterRegistry meters;

    public OutboxDispatcher(JdbcTemplate jdbc, TransactionTemplate transactions, DomainEventPublisher publisher,
                            @Value("${buyforu.events.instance-id:}") String configuredInstanceId,
                            @Value("${buyforu.outbox.max-per-cycle:100}") int maxPerCycle,
                            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.publisher = publisher;
        // 未配置时每次启动一个随机 ID，避免多实例都写成 buyforu-commerce-local。
        this.instanceId = configuredInstanceId == null || configuredInstanceId.isBlank()
                ? UUID.randomUUID().toString() : configuredInstanceId;
        this.maxPerCycle = maxPerCycle;
        this.meters = meters;
    }

    /**
     * 单周期最多投递 {@code buyforu.outbox.max-per-cycle} 条。
     *
     * <p>没有上限时，一次大积压（对端宕机恢复后的补投）会让这个循环长时间占着调度线程：
     * 固定延迟的下一轮排不上，Webhook 慢的时候更明显；停机时也不能及时收尾。
     * 触顶次数单独计数——它不是错误，但持续增长说明积压追不上来。</p>
     */
    @Scheduled(fixedDelayString = "${buyforu.outbox.poll-delay:PT1S}", scheduler = "outboxScheduler")
    public void dispatch() {
        int published = 0;
        while (published < maxPerCycle) {
            OutboxRow row = transactions.execute(status -> claimOne());
            if (row == null) return;
            try {
                NetworkCallGuard.assertNoTransaction("outbox");
                publisher.publish(row.event());
                transactions.executeWithoutResult(status -> markPublished(row.event().eventId()));
            } catch (RuntimeException failure) {
                transactions.executeWithoutResult(status -> markRetry(row, failure));
                return;
            }
            published++;
        }
        meters.counter("buyforu_outbox_cycle_capped_total").increment();
    }

    /** 认领后进程死了，60 秒后把 CLAIMED 打回 PENDING，不增加 attempts。 */
    @Scheduled(fixedDelayString = "${buyforu.outbox.reclaim-delay:PT30S}", scheduler = "outboxReclaimScheduler")
    public void reclaimStaleClaims() {
        transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE commerce_schema.outbox_event
                SET status = 'PENDING', claimed_at = NULL, claimed_by = NULL
                WHERE status = 'CLAIMED' AND claimed_at <= now() - interval '60 seconds'
                """));
    }

    private OutboxRow claimOne() {
        List<OutboxRow> events = jdbc.query("""
                SELECT event_id, aggregate_type, aggregate_id, event_type, payload::text, attempts
                FROM commerce_schema.outbox_event
                WHERE status = 'PENDING' AND next_attempt_at <= now()
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED LIMIT 1
                """, (result, row) -> new OutboxRow(new DomainEvent(result.getString(1), result.getString(2),
                result.getString(3), result.getString(4), result.getString(5)), result.getInt(6)));
        if (events.isEmpty()) return null;
        OutboxRow row = events.getFirst();
        jdbc.update("""
                UPDATE commerce_schema.outbox_event
                SET status = 'CLAIMED', claimed_at = now(), claimed_by = ?
                WHERE event_id = ? AND status = 'PENDING'
                """, instanceId, row.event().eventId());
        return row;
    }

    private void markPublished(String eventId) {
        jdbc.update("""
                UPDATE commerce_schema.outbox_event
                SET status = 'PUBLISHED', published_at = now(), last_error = NULL,
                    claimed_at = NULL, claimed_by = NULL
                WHERE event_id = ? AND status = 'CLAIMED' AND claimed_by = ?
                """, eventId, instanceId);
    }

    private void markRetry(OutboxRow row, RuntimeException failure) {
        int attempts = row.attempts() + 1;
        Duration retryAfter = Duration.ofSeconds(Math.min(300L, 1L << Math.min(attempts, 8)));
        jdbc.update("""
                UPDATE commerce_schema.outbox_event
                SET attempts = ?,
                    status = CASE WHEN ? >= ? THEN 'FAILED' ELSE 'PENDING' END,
                    next_attempt_at = now() + (? * interval '1 second'),
                    last_error = ?,
                    claimed_at = NULL,
                    claimed_by = NULL
                WHERE event_id = ? AND status = 'CLAIMED' AND claimed_by = ?
                """, attempts, attempts, MAX_ATTEMPTS, retryAfter.toSeconds(),
                truncate(failure.getMessage()), row.event().eventId(), instanceId);
    }

    private static String truncate(String value) {
        if (value == null) return "publisher failure without message";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private record OutboxRow(DomainEvent event, int attempts) { }
}
